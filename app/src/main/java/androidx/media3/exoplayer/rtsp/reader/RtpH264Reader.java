/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.exoplayer.rtsp.reader.RtpReaderUtils.toSampleTimeUs;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.exoplayer.rtsp.RtpPacket;
import androidx.media3.exoplayer.rtsp.RtpPayloadFormat;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses an H264 byte stream carried on RTP packets, and extracts H264 Access Units. */
/* package */ final class RtpH264Reader implements RtpPayloadReader {
  private static final String TAG = "RtpH264Reader";

  private static final int MEDIA_CLOCK_FREQUENCY = 90_000;

  /** Offset of payload data within a FU type A payload. */
  private static final int FU_PAYLOAD_OFFSET = 2;

  /** Single Time Aggregation Packet type A. */
  private static final int RTP_PACKET_TYPE_STAP_A = 24;

  /** Fragmentation Unit type A. */
  private static final int RTP_PACKET_TYPE_FU_A = 28;

  /** IDR NAL unit type. */
  private static final int NAL_UNIT_TYPE_IDR = 5;

  /** Scratch for Fragmentation Unit RTP packets. */
  private final ParsableByteArray fuScratchBuffer;

  private final ParsableByteArray nalStartCodeArray =
      new ParsableByteArray(NalUnitUtil.NAL_START_CODE);

  private final RtpPayloadFormat payloadFormat;

  private @MonotonicNonNull TrackOutput trackOutput;
  private @C.BufferFlags int bufferFlags;

  private long firstReceivedTimestamp;
  private int previousSequenceNumber;

  /** The combined size of a sample that is fragmented into multiple RTP packets. */
  private int fragmentedSampleSizeBytes;

  private long startTimeOffsetUs;

  // TV VMS: si el SDP no trae sprop-parameter-sets, se esperan el SPS y el PPS del propio flujo y
  // no se entrega ninguna muestra al decodificador hasta tenerlos (así arranca con el formato bueno).
  private boolean needInbandParameterSets;
  private boolean formatCompleted;
  private byte[] inbandSps;
  private byte[] inbandPps;

  /** Creates an instance. */
  public RtpH264Reader(RtpPayloadFormat payloadFormat) {
    this.payloadFormat = payloadFormat;
    fuScratchBuffer = new ParsableByteArray();
    firstReceivedTimestamp = C.TIME_UNSET;
    previousSequenceNumber = C.INDEX_UNSET;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, int trackId) {
    trackOutput = extractorOutput.track(trackId, C.TRACK_TYPE_VIDEO);

    castNonNull(trackOutput).format(payloadFormat.format);
    needInbandParameterSets = payloadFormat.format.initializationData.isEmpty();
    formatCompleted = !needInbandParameterSets;
  }

  /**
   * Mientras falten SPS/PPS: guarda los que lleguen y descarta el paquete. Devuelve true si el
   * paquete no debe procesarse.
   */
  private boolean dropWhileWaitingForParameterSets(ParsableByteArray data, int packetMode) {
    if (formatCompleted) {
      return false;
    }
    byte[] array = data.getData();
    int position = data.getPosition();
    if (packetMode > 0 && packetMode < 24) {
      captureParameterSet(array, position, data.bytesLeft());
    } else if (packetMode == RTP_PACKET_TYPE_STAP_A) {
      int pos = position + 1;
      int limit = data.limit();
      while (pos + 2 < limit) {
        int length = ((array[pos] & 0xFF) << 8) | (array[pos + 1] & 0xFF);
        pos += 2;
        if (length <= 0 || pos + length > limit) {
          break;
        }
        captureParameterSet(array, pos, length);
        pos += length;
      }
    }
    return true;
  }

  private void captureParameterSet(byte[] array, int offset, int length) {
    if (length < 2 || offset + length > array.length) {
      return;
    }
    int type = array[offset] & 0x1F;
    if (type == 7) {
      inbandSps = Arrays.copyOfRange(array, offset, offset + length);
    } else if (type == 8) {
      inbandPps = Arrays.copyOfRange(array, offset, offset + length);
    }
    if (inbandSps != null && inbandPps != null && trackOutput != null) {
      try {
        byte[] sps = withStartCode(inbandSps);
        byte[] pps = withStartCode(inbandPps);
        NalUnitUtil.SpsData spsData =
            NalUnitUtil.parseSpsNalUnit(sps, NalUnitUtil.NAL_START_CODE.length, sps.length);
        Format completed =
            payloadFormat
                .format
                .buildUpon()
                .setInitializationData(ImmutableList.of(sps, pps))
                .setWidth(spsData.width)
                .setHeight(spsData.height)
                .setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
                .build();
        trackOutput.format(completed);
        formatCompleted = true;
      } catch (RuntimeException e) {
        Log.w(TAG, "No se pudo leer el SPS del flujo", e);
        inbandSps = null;
        inbandPps = null;
      }
    }
  }

  private static byte[] withStartCode(byte[] nal) {
    byte[] out = new byte[NalUnitUtil.NAL_START_CODE.length + nal.length];
    System.arraycopy(NalUnitUtil.NAL_START_CODE, 0, out, 0, NalUnitUtil.NAL_START_CODE.length);
    System.arraycopy(nal, 0, out, NalUnitUtil.NAL_START_CODE.length, nal.length);
    return out;
  }

  @Override
  public void onReceivingFirstPacket(long timestamp, int sequenceNumber) {}

  @Override
  public void consume(ParsableByteArray data, long timestamp, int sequenceNumber, boolean rtpMarker)
      throws ParserException {

    int rtpH264PacketMode;
    try {
      // RFC6184 Section 5.6, 5.7 and 5.8.
      rtpH264PacketMode = data.getData()[0] & 0x1F;
    } catch (IndexOutOfBoundsException e) {
      throw ParserException.createForMalformedManifest(/* message= */ null, e);
    }

    checkStateNotNull(trackOutput);
    if (needInbandParameterSets && dropWhileWaitingForParameterSets(data, rtpH264PacketMode)) {
      previousSequenceNumber = sequenceNumber;
      return;
    }
    if (rtpH264PacketMode > 0 && rtpH264PacketMode < 24) {
      processSingleNalUnitPacket(data);
    } else if (rtpH264PacketMode == RTP_PACKET_TYPE_STAP_A) {
      processSingleTimeAggregationPacket(data);
    } else if (rtpH264PacketMode == RTP_PACKET_TYPE_FU_A) {
      processFragmentationUnitPacket(data, sequenceNumber);
    } else {
      throw ParserException.createForMalformedManifest(
          String.format("RTP H264 packetization mode [%d] not supported.", rtpH264PacketMode),
          /* cause= */ null);
    }

    if (rtpMarker) {
      if (firstReceivedTimestamp == C.TIME_UNSET) {
        firstReceivedTimestamp = timestamp;
      }

      long timeUs =
          toSampleTimeUs(
              startTimeOffsetUs, timestamp, firstReceivedTimestamp, MEDIA_CLOCK_FREQUENCY);
      trackOutput.sampleMetadata(
          timeUs, bufferFlags, fragmentedSampleSizeBytes, /* offset= */ 0, /* cryptoData= */ null);
      fragmentedSampleSizeBytes = 0;
    }

    previousSequenceNumber = sequenceNumber;
  }

  @Override
  public void seek(long nextRtpTimestamp, long timeUs) {
    firstReceivedTimestamp = nextRtpTimestamp;
    fragmentedSampleSizeBytes = 0;
    startTimeOffsetUs = timeUs;
  }

  // Internal methods.

  /**
   * Processes Single NAL Unit packet (RFC6184 Section 5.6).
   *
   * <p>Outputs the single NAL Unit (with start code prepended) to {@link #trackOutput}. Sets {@link
   * #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleNalUnitPacket(ParsableByteArray data) {
    // Example of a Single Nal Unit packet
    //    0                   1                   2                   3
    //    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |F|NRI|  Type   |                                               |
    //    +-+-+-+-+-+-+-+-+                                               |
    //    |                                                               |
    //    |               Bytes 2..n of a single NAL unit                 |
    //    |                                                               |
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    int numBytesInData = data.bytesLeft();
    fragmentedSampleSizeBytes += writeStartCode();
    trackOutput.sampleData(data, numBytesInData);
    fragmentedSampleSizeBytes += numBytesInData;

    int nalHeaderType = data.getData()[0] & 0x1F;
    bufferFlags = getBufferFlagsFromNalType(nalHeaderType);
  }

  /**
   * Processes STAP Type A packet (RFC6184 Section 5.7).
   *
   * <p>Outputs the received aggregation units (with start code prepended) to {@link #trackOutput}.
   * Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processSingleTimeAggregationPacket(ParsableByteArray data) {
    //  Example of an STAP-A packet.
    //      0                   1                   2                   3
    //     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                          RTP Header                           |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |STAP-A NAL HDR |         NALU 1 Size           | NALU 1 HDR    |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         NALU 1 Data                           |
    //    :                                                               :
    //    +               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |               | NALU 2 Size                   | NALU 2 HDR    |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                         NALU 2 Data                           |
    //    :                                                               :
    //    |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //    |                               :...OPTIONAL RTP padding        |
    //    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+

    // Skips STAP-A NAL HDR that has the NAL format |F|NRI|Type|, but with Type replaced by the
    // STAP-A type id (RTP_PACKET_TYPE_STAP_A).
    data.readUnsignedByte();

    // Gets all NAL units until the remaining bytes are only enough to store an RTP padding.
    int nalUnitLength;
    while (data.bytesLeft() > 4) {
      nalUnitLength = data.readUnsignedShort();
      fragmentedSampleSizeBytes += writeStartCode();
      trackOutput.sampleData(data, nalUnitLength);
      fragmentedSampleSizeBytes += nalUnitLength;
    }

    // Treat Aggregated NAL units as non key frames.
    bufferFlags = 0;
  }

  /**
   * Processes Fragmentation Unit Type A packet (RFC6184 Section 5.8).
   *
   * <p>This method will be invoked multiple times to receive a single frame that is broken down
   * into a series of fragmentation units in multiple RTP packets.
   *
   * <p>Outputs the received fragmentation units (with start code prepended) to {@link
   * #trackOutput}. Sets {@link #bufferFlags} and {@link #fragmentedSampleSizeBytes} accordingly.
   */
  @RequiresNonNull("trackOutput")
  private void processFragmentationUnitPacket(ParsableByteArray data, int packetSequenceNumber) {
    //  FU-A mode packet layout.
    //   0                   1                   2                   3
    //   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  | FU indicator  |   FU header   |                               |
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
    //  |                                                               |
    //  |                         FU payload                            |
    //  |                                                               |
    //  |                               +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  |                               :...OPTIONAL RTP padding        |
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //
    //     FU Indicator     FU Header
    //   0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
    //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    //  |F|NRI|  Type   |S|E|R|  Type   |
    //  +---------------+---------------+
    //  Indicator: Upper 3 bits are the same as NALU header, Type = 28 (FU-A type).
    //  Header: Start/End/Reserved/Type. Type is same as NALU type.
    int fuIndicator = data.getData()[0];
    int fuHeader = data.getData()[1];
    int nalHeader = (fuIndicator & 0xE0) | (fuHeader & 0x1F);
    boolean isFirstFuPacket = (fuHeader & 0x80) > 0;
    boolean isLastFuPacket = (fuHeader & 0x40) > 0;

    if (isFirstFuPacket) {
      // Prepends starter code.
      fragmentedSampleSizeBytes += writeStartCode();

      // The bytes needed is 1 (NALU header) + payload size. The original data array has size 2 (FU
      // indicator/header) + payload size. Thus setting the correct header and set position to 1.
      data.getData()[1] = (byte) nalHeader;
      fuScratchBuffer.reset(data.getData());
      fuScratchBuffer.setPosition(1);
    } else {
      // Check that this packet is in the sequence of the previous packet.
      int expectedSequenceNumber = RtpPacket.getNextSequenceNumber(previousSequenceNumber);
      if (packetSequenceNumber != expectedSequenceNumber) {
        Log.w(
            TAG,
            Util.formatInvariant(
                "Received RTP packet with unexpected sequence number. Expected: %d; received: %d."
                    + " Dropping packet.",
                expectedSequenceNumber, packetSequenceNumber));
        return;
      }

      // Setting position to ignore FU indicator and header.
      fuScratchBuffer.reset(data.getData());
      fuScratchBuffer.setPosition(FU_PAYLOAD_OFFSET);
    }

    int fragmentSize = fuScratchBuffer.bytesLeft();
    trackOutput.sampleData(fuScratchBuffer, fragmentSize);
    fragmentedSampleSizeBytes += fragmentSize;

    if (isLastFuPacket) {
      bufferFlags = getBufferFlagsFromNalType(nalHeader & 0x1F);
    }
  }

  private int writeStartCode() {
    nalStartCodeArray.setPosition(/* position= */ 0);
    int bytesWritten = nalStartCodeArray.bytesLeft();
    checkNotNull(trackOutput).sampleData(nalStartCodeArray, bytesWritten);
    return bytesWritten;
  }

  private static @C.BufferFlags int getBufferFlagsFromNalType(int nalType) {
    return nalType == NAL_UNIT_TYPE_IDR ? C.BUFFER_FLAG_KEY_FRAME : 0;
  }
}
