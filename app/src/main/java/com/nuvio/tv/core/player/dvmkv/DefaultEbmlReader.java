/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.nuvio.tv.core.player.dvmkv;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.extractor.ExtractorInput;
import java.io.EOFException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Default implementation of {@link EbmlReader}. */
/* package */ final class DefaultEbmlReader implements EbmlReader {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    ELEMENT_STATE_READ_ID,
    ELEMENT_STATE_READ_CONTENT_SIZE,
    ELEMENT_STATE_READ_CONTENT,
    ELEMENT_STATE_RESYNC
  })
  private @interface ElementState {}

  private static final String TAG = "DefaultEbmlReader";

  private static final int ELEMENT_STATE_READ_ID = 0;
  private static final int ELEMENT_STATE_READ_CONTENT_SIZE = 1;
  private static final int ELEMENT_STATE_READ_CONTENT = 2;
  // Searching byte by byte for the next level 1 element after an element whose size cannot be
  // valid. Kept as a state so a read error during the search resumes the search, not ID parsing.
  private static final int ELEMENT_STATE_RESYNC = 3;

  /** Matroska Segment, the only master element kept on the stack across a resync. */
  private static final int ID_SEGMENT = 0x18538067;
  /** Matroska Block and SimpleBlock, whose content starts with a header MatroskaExtractor parses. */
  private static final int ID_BLOCK = 0xA1;
  private static final int ID_SIMPLE_BLOCK = 0xA3;

  private static final int LACING_NONE = 0;
  private static final int LACING_XIPH = 1;
  private static final int LACING_FIXED_SIZE = 2;
  private static final int LACING_EBML = 3;

  /** Returned by {@link #peekBlockByte} when the block header runs past the end of the block. */
  private static final int PAST_BLOCK_END = -1;

  private static final int MAX_ID_BYTES = 4;
  private static final int MAX_LENGTH_BYTES = 8;

  private static final int MAX_INTEGER_ELEMENT_SIZE_BYTES = 8;
  private static final int VALID_FLOAT32_ELEMENT_SIZE_BYTES = 4;
  private static final int VALID_FLOAT64_ELEMENT_SIZE_BYTES = 8;

  private final byte[] scratch;
  private final ArrayDeque<MasterElement> masterElementsStack;
  private final VarintReader varintReader;

  private @MonotonicNonNull EbmlProcessor processor;
  private @ElementState int elementState;
  private int elementId;
  private long elementContentSize;
  // True once the content of the current binary element has been handed to the processor. A read
  // error inside the processor hands the same element over again to resume it; by then its first
  // byte has been read, so it must not be checked again.
  private boolean binaryContentStarted;
  // Bytes of the current block peeked by findBlockDamage.
  private int blockBytesPeeked;

  public DefaultEbmlReader() {
    scratch = new byte[8];
    masterElementsStack = new ArrayDeque<>();
    varintReader = new VarintReader();
  }

  @Override
  public void init(EbmlProcessor processor) {
    this.processor = processor;
  }

  @Override
  public void reset() {
    elementState = ELEMENT_STATE_READ_ID;
    masterElementsStack.clear();
    varintReader.reset();
  }

  @Override
  public boolean read(ExtractorInput input) throws IOException {
    checkNotNull(processor);
    while (true) {
      MasterElement head = masterElementsStack.peek();
      if (head != null && input.getPosition() >= head.elementEndPosition) {
        processor.endMasterElement(masterElementsStack.pop().elementId);
        return true;
      }

      if (elementState == ELEMENT_STATE_RESYNC) {
        long result = maybeResyncToNextLevel1Element(input);
        if (result == C.RESULT_END_OF_INPUT) {
          return false;
        }
        elementId = (int) result;
        elementState = ELEMENT_STATE_READ_CONTENT_SIZE;
      }

      if (elementState == ELEMENT_STATE_READ_ID) {
        long result = varintReader.readUnsignedVarint(input, true, false, MAX_ID_BYTES);
        if (result == C.RESULT_MAX_LENGTH_EXCEEDED
            && varintReader.getLastLength() == C.LENGTH_UNSET) {
          // The first byte was zero. No varint starts with a zero byte, so the reader has lost its
          // place. startResync closes the Cluster and BlockGroup the damage left open; the search
          // alone would leave them open and carry the old block's state into the next block.
          startResync(input, "Invalid element id");
          continue;
        }
        if (result == C.RESULT_MAX_LENGTH_EXCEEDED) {
          result = maybeResyncToNextLevel1Element(input);
        }
        if (result == C.RESULT_END_OF_INPUT) {
          return false;
        }
        // Element IDs are at most 4 bytes, so we can cast to integers.
        elementId = (int) result;
        elementState = ELEMENT_STATE_READ_CONTENT_SIZE;
      }

      if (elementState == ELEMENT_STATE_READ_CONTENT_SIZE) {
        long size;
        try {
          size = varintReader.readUnsignedVarint(input, false, true, MAX_LENGTH_BYTES);
        } catch (ParserException e) {
          // The first byte of the size was zero, which no varint starts with. VarintReader throws
          // after consuming that byte and resetting itself, so it is ready for the next varint.
          startResync(input, "Element 0x" + Integer.toHexString(elementId) + " has an invalid size");
          continue;
        }
        elementContentSize = size;
        elementState = ELEMENT_STATE_READ_CONTENT;
        binaryContentStarted = false;
      }

      @EbmlProcessor.ElementType int type = processor.getElementType(elementId);
      switch (type) {
        case EbmlProcessor.ELEMENT_TYPE_MASTER:
          long elementContentPosition = input.getPosition();
          long elementEndPosition = elementContentPosition + elementContentSize;
          masterElementsStack.push(new MasterElement(elementId, elementEndPosition));
          processor.startMasterElement(elementId, elementContentPosition, elementContentSize);
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT:
          if (elementContentSize > MAX_INTEGER_ELEMENT_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer(
                "Invalid integer size: " + elementContentSize, /* cause= */ null);
          }
          processor.integerElement(elementId, readInteger(input, (int) elementContentSize));
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_FLOAT:
          if (elementContentSize != VALID_FLOAT32_ELEMENT_SIZE_BYTES
              && elementContentSize != VALID_FLOAT64_ELEMENT_SIZE_BYTES) {
            throw ParserException.createForMalformedContainer(
                "Invalid float size: " + elementContentSize, /* cause= */ null);
          }
          processor.floatElement(elementId, readFloat(input, (int) elementContentSize));
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_STRING:
          if (elementContentSize > Integer.MAX_VALUE) {
            throw ParserException.createForMalformedContainer(
                "String element size: " + elementContentSize, /* cause= */ null);
          }
          processor.stringElement(elementId, readString(input, (int) elementContentSize));
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_BINARY:
          if (elementContentSize > Integer.MAX_VALUE) {
            startResync(input, oversizeReason());
            break;
          }
          @Nullable String blockDamage = binaryContentStarted ? null : findBlockDamage(input);
          if (blockDamage != null) {
            Log.w(
                TAG,
                "Block at position "
                    + input.getPosition()
                    + " has "
                    + blockDamage
                    + "; skipping it");
            input.skipFully((int) elementContentSize);
            elementState = ELEMENT_STATE_READ_ID;
            return true;
          }
          binaryContentStarted = true;
          processor.binaryElement(elementId, (int) elementContentSize, input);
          elementState = ELEMENT_STATE_READ_ID;
          return true;
        case EbmlProcessor.ELEMENT_TYPE_UNKNOWN:
          if (elementContentSize > Integer.MAX_VALUE) {
            startResync(input, oversizeReason());
            break;
          }
          input.skipFully((int) elementContentSize);
          elementState = ELEMENT_STATE_READ_ID;
          elementContentSize = 0;
          elementId = 0;
          break;
        default:
          throw ParserException.createForMalformedContainer(
              "Invalid element type " + type, /* cause= */ null);
      }
    }
  }

  /**
   * Checks the header of the current element if it is a Block or SimpleBlock, and returns what is
   * damaged in it, or null when it is intact (or the element is not a block).
   *
   * <p>A block starts with its track number as a varint, a timecode, flags and, for a laced block,
   * the sizes of its frames. Damaged data there makes MatroskaExtractor fail ("No valid varint
   * length mask found", "EBML lacing sample size out of range") or produce frame sizes below zero
   * or beyond the block, which ends playback. The element's own size was read fine, so only this
   * block is lost when the reader skips it. The header is checked as MatroskaExtractor parses it.
   * It is only peeked, so a read error here leaves the input where the block starts.
   */
  @Nullable
  private String findBlockDamage(ExtractorInput input) throws IOException {
    if ((elementId != ID_BLOCK && elementId != ID_SIMPLE_BLOCK) || elementContentSize == 0) {
      return null;
    }
    blockBytesPeeked = 0;
    try {
      return peekBlockDamage(input, (int) elementContentSize);
    } finally {
      input.resetPeekPosition();
    }
  }

  @Nullable
  private String peekBlockDamage(ExtractorInput input, int blockSize) throws IOException {
    int firstByte = peekBlockByte(input, blockSize);
    if (firstByte == 0) {
      return "an invalid track number";
    }
    int trackNumberLength = VarintReader.parseUnsignedVarintLength(firstByte);
    // The rest of the track number, the timecode (2 bytes) and the flags (1 byte).
    int flags = 0;
    for (int i = 1; i < trackNumberLength + 3; i++) {
      flags = peekBlockByte(input, blockSize);
      if (flags == PAST_BLOCK_END) {
        return "a header longer than the block";
      }
    }
    int lacing = (flags & 0x06) >> 1;
    if (lacing == LACING_NONE) {
      return null;
    }
    int frameCountByte = peekBlockByte(input, blockSize);
    if (frameCountByte == PAST_BLOCK_END) {
      return "a header longer than the block";
    }
    int frameCount = frameCountByte + 1;
    if (lacing == LACING_FIXED_SIZE) {
      return null;
    }
    // Sizes of all frames but the last, which takes what is left of the block.
    long totalFramesSize = 0;
    long frameSize = 0;
    for (int frame = 0; frame < frameCount - 1; frame++) {
      if (lacing == LACING_XIPH) {
        frameSize = 0;
        int sizeByte;
        do {
          sizeByte = peekBlockByte(input, blockSize);
          if (sizeByte == PAST_BLOCK_END) {
            return "a header longer than the block";
          }
          frameSize += sizeByte;
        } while (sizeByte == 0xFF);
      } else {
        // LACING_EBML: the first size is a varint, later ones are signed varint offsets.
        int sizeFirstByte = peekBlockByte(input, blockSize);
        if (sizeFirstByte == PAST_BLOCK_END) {
          return "a header longer than the block";
        }
        if (sizeFirstByte == 0) {
          return "an invalid lacing size";
        }
        int sizeLength = VarintReader.parseUnsignedVarintLength(sizeFirstByte);
        long value = sizeFirstByte & ~(0x80 >> (sizeLength - 1));
        for (int i = 1; i < sizeLength; i++) {
          int sizeByte = peekBlockByte(input, blockSize);
          if (sizeByte == PAST_BLOCK_END) {
            return "a header longer than the block";
          }
          value = (value << 8) | sizeByte;
        }
        if (frame > 0) {
          value -= (1L << (6 + (sizeLength - 1) * 7)) - 1;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
          return "a lacing size out of range";
        }
        frameSize = frame == 0 ? value : frameSize + value;
      }
      if (frameSize < 0) {
        return "a negative lacing size";
      }
      totalFramesSize += frameSize;
      if (totalFramesSize > blockSize - blockBytesPeeked) {
        return "lacing sizes larger than the block";
      }
    }
    return null;
  }

  /**
   * Peeks the next byte of the current block, or returns {@link #PAST_BLOCK_END} when the block
   * has no more bytes.
   */
  private int peekBlockByte(ExtractorInput input, int blockSize) throws IOException {
    if (blockBytesPeeked >= blockSize) {
      return PAST_BLOCK_END;
    }
    input.peekFully(scratch, 0, 1);
    blockBytesPeeked++;
    return scratch[0] & 0xFF;
  }

  /**
   * Describes a binary or unknown element whose size does not fit in an int. No real Matroska
   * element of these types is that large, so the header just read is noise. Casting the size to an
   * int wraps it negative, and skipping a negative length throws an unchecked exception inside
   * {@link ExtractorInput} that ends playback.
   */
  private String oversizeReason() {
    return "Element 0x" + Integer.toHexString(elementId) + " declares " + elementContentSize
        + " bytes";
  }

  /**
   * Gives up on the current element after damaged data (in the file or the stream) and starts the
   * search for the next level 1 element.
   *
   * <p>Used when an element's size does not fit in an int (see {@link #oversizeReason()}) and when
   * a zero byte sits where an element id or size starts. Either way the reader has lost its place,
   * so instead of ending playback the elements opened below the Segment are closed and the reader
   * searches for the next level 1 element (normally the next Cluster), so playback carries on from
   * there.
   */
  @RequiresNonNull("processor")
  private void startResync(ExtractorInput input, String reason) throws ParserException {
    Log.w(
        TAG,
        reason
            + " at position "
            + input.getPosition()
            + "; resyncing to the next level 1 element");
    elementState = ELEMENT_STATE_RESYNC;
    elementContentSize = 0;
    elementId = 0;
    // A level 1 element found by the search is a child of the Segment, so whatever was open below
    // the Segment has ended. Closing those elements commits a block that was already read in full
    // and resets the block state; leaving them open would apply the old block's state to the
    // first block found after the search.
    MasterElement head;
    while ((head = masterElementsStack.peek()) != null && head.elementId != ID_SEGMENT) {
      masterElementsStack.pop();
      processor.endMasterElement(head.elementId);
    }
  }

  /**
   * Does a byte by byte search to try and find the next level 1 element. This method is called if
   * some invalid data is encountered in the parser.
   *
   * @param input The {@link ExtractorInput} from which data has to be read.
   * @return id of the next level 1 element that has been found.
   * @throws EOFException If the end of input was encountered when searching for the next level 1
   *     element.
   * @throws IOException If an error occurs reading from the input.
   */
  @RequiresNonNull("processor")
  private long maybeResyncToNextLevel1Element(ExtractorInput input) throws IOException {
    input.resetPeekPosition();
    while (true) {
      if (!peekFullyOrEnd(input, scratch, MAX_ID_BYTES)) {
        return C.RESULT_END_OF_INPUT;
      }
      int varintLength = VarintReader.parseUnsignedVarintLength(scratch[0]);
      if (varintLength != C.LENGTH_UNSET && varintLength <= MAX_ID_BYTES) {
        int potentialId = (int) VarintReader.assembleVarint(scratch, varintLength, false);
        if (processor.isLevel1Element(potentialId)) {
          input.skipFully(varintLength);
          return potentialId;
        }
      }
      try {
        input.skipFully(1);
      } catch (EOFException e) {
        return C.RESULT_END_OF_INPUT;
      }
    }
  }

  private static boolean peekFullyOrEnd(ExtractorInput input, byte[] target, int length)
      throws IOException {
    long streamLength = input.getLength();
    if (streamLength != C.LENGTH_UNSET && streamLength - input.getPeekPosition() < length) {
      return false;
    }
    try {
      return input.peekFully(target, 0, length, /* allowEndOfInput= */ true);
    } catch (EOFException e) {
      return false;
    }
  }

  /**
   * Reads and returns an integer of length {@code byteLength} from the {@link ExtractorInput}.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @param byteLength The length of the integer being read.
   * @return The read integer value.
   * @throws IOException If an error occurs reading from the input.
   */
  private long readInteger(ExtractorInput input, int byteLength) throws IOException {
    input.readFully(scratch, 0, byteLength);
    long value = 0;
    for (int i = 0; i < byteLength; i++) {
      value = (value << 8) | (scratch[i] & 0xFF);
    }
    return value;
  }

  /**
   * Reads and returns a float of length {@code byteLength} from the {@link ExtractorInput}.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @param byteLength The length of the float being read.
   * @return The read float value.
   * @throws IOException If an error occurs reading from the input.
   */
  private double readFloat(ExtractorInput input, int byteLength) throws IOException {
    long integerValue = readInteger(input, byteLength);
    double floatValue;
    if (byteLength == VALID_FLOAT32_ELEMENT_SIZE_BYTES) {
      floatValue = Float.intBitsToFloat((int) integerValue);
    } else {
      floatValue = Double.longBitsToDouble(integerValue);
    }
    return floatValue;
  }

  /**
   * Reads a string of length {@code byteLength} from the {@link ExtractorInput}. Zero padding is
   * removed, so the returned string may be shorter than {@code byteLength}.
   *
   * @param input The {@link ExtractorInput} from which to read.
   * @param byteLength The length of the string being read, including zero padding.
   * @return The read string value.
   * @throws IOException If an error occurs reading from the input.
   */
  private static String readString(ExtractorInput input, int byteLength) throws IOException {
    if (byteLength == 0) {
      return "";
    }
    byte[] stringBytes = new byte[byteLength];
    input.readFully(stringBytes, 0, byteLength);
    // Remove zero padding.
    int trimmedLength = byteLength;
    while (trimmedLength > 0 && stringBytes[trimmedLength - 1] == 0) {
      trimmedLength--;
    }
    return new String(stringBytes, 0, trimmedLength);
  }

  /**
   * Used in {@link #masterElementsStack} to track when the current master element ends, so that
   * {@link EbmlProcessor#endMasterElement(int)} can be called.
   */
  private static final class MasterElement {

    private final int elementId;
    private final long elementEndPosition;

    private MasterElement(int elementId, long elementEndPosition) {
      this.elementId = elementId;
      this.elementEndPosition = elementEndPosition;
    }
  }
}
