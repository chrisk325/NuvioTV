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
  /** Matroska Block and SimpleBlock, whose content starts with the track number as a varint. */
  private static final int ID_BLOCK = 0xA1;
  private static final int ID_SIMPLE_BLOCK = 0xA3;

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
          if (!binaryContentStarted && hasInvalidTrackNumber(input)) {
            Log.w(
                TAG,
                "Block at position "
                    + input.getPosition()
                    + " has an invalid track number; skipping it");
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
   * Returns whether the current element is a Block or SimpleBlock whose first byte is zero.
   *
   * <p>A block starts with its track number as a varint, and no varint starts with a zero byte, so
   * the block is damaged. MatroskaExtractor would fail on it with "No valid varint length mask
   * found", which ends playback. The element's own size was read fine, so only this block is lost
   * when the reader skips it. The byte is peeked, so a read error here leaves the input where the
   * block starts.
   */
  private boolean hasInvalidTrackNumber(ExtractorInput input) throws IOException {
    if ((elementId != ID_BLOCK && elementId != ID_SIMPLE_BLOCK) || elementContentSize == 0) {
      return false;
    }
    input.peekFully(scratch, 0, 1);
    input.resetPeekPosition();
    return scratch[0] == 0;
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
