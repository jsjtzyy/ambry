/*
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.store;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.config.StoreConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.Pair;
import com.github.ambry.utils.TestUtils;
import com.github.ambry.utils.Time;
import com.github.ambry.utils.Utils;
import com.github.ambry.utils.UtilsTest;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.junit.Assert.*;


/**
 * Tests for {@link IndexSegment}s
 */
@RunWith(Parameterized.class)
public class IndexSegmentTest {
  private static final int CUSTOM_ID_SIZE = 10;
  private static final int KEY_SIZE = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE)).sizeInBytes();
  private static final int SMALLER_KEY_SIZE = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE / 2)).sizeInBytes();
  private static final int LARGER_KEY_SIZE =
      new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE + CUSTOM_ID_SIZE / 2)).sizeInBytes();
  private static final StoreConfig STORE_CONFIG = new StoreConfig(new VerifiableProperties(new Properties()));
  private static final MockTime time = new MockTime();
  private static final long DELETE_FILE_SPAN_SIZE = 10;
  private static final StoreKeyFactory STORE_KEY_FACTORY;

  static {
    try {
      STORE_KEY_FACTORY = Utils.getObj("com.github.ambry.store.MockIdFactory");
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private final File tempDir;
  private final StoreMetrics metrics;
  private final short version;

  /**
   * Running for {@link PersistentIndex#VERSION_0} and {@link PersistentIndex#VERSION_1}
   * @return an array with both the versions ({@link PersistentIndex#VERSION_0} and {@link PersistentIndex#VERSION_1}).
   */
  @Parameterized.Parameters
  public static List<Object[]> data() {
    return Arrays.asList(
        new Object[][]{{PersistentIndex.VERSION_0}, {PersistentIndex.VERSION_1}, {PersistentIndex.VERSION_2}});
  }

  /**
   * Creates a temporary directory and sets up metrics.
   * @throws IOException
   */
  public IndexSegmentTest(short version) throws IOException {
    tempDir = StoreTestUtils.createTempDirectory("indexSegmentDir-" + UtilsTest.getRandomString(10));
    MetricRegistry metricRegistry = new MetricRegistry();
    metrics = new StoreMetrics(metricRegistry);
    this.version = version;
  }

  /**
   * Deletes the temporary directory.
   * @throws IOException
   */
  @After
  public void cleanup() throws IOException {
    assertTrue(tempDir.getAbsolutePath() + " could not be deleted", StoreTestUtils.cleanDirectory(tempDir, true));
  }

  /**
   * Comprehensive tests for {@link IndexSegment}
   */
  @Test
  public void comprehensiveTest() throws IOException, StoreException {
    if (version == PersistentIndex.VERSION_2) {
      for (boolean includeSmall : new boolean[]{false, true}) {
        for (boolean includeLarge : new boolean[]{false, true}) {
          doComprehensiveTest(version, includeSmall, includeLarge);
        }
      }
    } else {
      doComprehensiveTest(version, false, false);
    }
  }

  /**
   * Tests the case when {@link IndexSegment#writeIndexSegmentToFile(Offset)} is provided with different offsets <=
   * {@link IndexSegment#getEndOffset()} and makes sure that only the relevant parts of the segment are written to disk.
   * @throws IOException
   * @throws StoreException
   */
  @Test
  public void partialWriteTest() throws IOException, StoreException {
    String prevLogSegmentName = LogSegmentNameHelper.getName(0, 0);
    String logSegmentName = LogSegmentNameHelper.getNextPositionName(prevLogSegmentName);
    Offset startOffset = new Offset(logSegmentName, 0);
    MockId id1 = new MockId("0" + UtilsTest.getRandomString(CUSTOM_ID_SIZE - 1));
    MockId id2 = new MockId("1" + UtilsTest.getRandomString(CUSTOM_ID_SIZE - 1));
    MockId id3 = new MockId("2" + UtilsTest.getRandomString(CUSTOM_ID_SIZE - 1));
    short accountId = Utils.getRandomShort(TestUtils.RANDOM);
    short containerId = Utils.getRandomShort(TestUtils.RANDOM);
    IndexValue value1 =
        IndexValueTest.getIndexValue(1000, new Offset(logSegmentName, 0), Utils.Infinite_Time, time.milliseconds(),
            accountId, containerId, version);
    IndexValue value2 =
        IndexValueTest.getIndexValue(1000, new Offset(logSegmentName, 1000), Utils.Infinite_Time, time.milliseconds(),
            accountId, containerId, version);
    IndexValue value3 =
        IndexValueTest.getIndexValue(1000, new Offset(logSegmentName, 2000), Utils.Infinite_Time, time.milliseconds(),
            accountId, containerId, version);
    time.sleep(TimeUnit.SECONDS.toMillis(1));
    // generate a DELETE
    IndexValue delValue2 =
        IndexValueTest.getIndexValue(value2.getSize(), value2.getOffset(), value2.getExpiresAtMs(), time.milliseconds(),
            value2.getAccountId(), value2.getContainerId(), version);
    delValue2.setNewOffset(new Offset(logSegmentName, 3000));
    delValue2.setNewSize(100);
    delValue2.setFlag(IndexValue.Flags.Delete_Index);
    IndexSegment indexSegment = generateIndexSegment(startOffset);
    // inserting in the opposite order by design to ensure that writes are based on offset ordering and not key ordering
    indexSegment.addEntry(new IndexEntry(id3, value1), new Offset(logSegmentName, 1000));
    indexSegment.addEntry(new IndexEntry(id2, value2), new Offset(logSegmentName, 2000));
    indexSegment.addEntry(new IndexEntry(id1, value3), new Offset(logSegmentName, 3000));
    indexSegment.addEntry(new IndexEntry(id2, delValue2), new Offset(logSegmentName, 3100));

    // provide end offsets such that nothing is written
    checkNonCreationOfIndexSegmentFile(indexSegment, new Offset(prevLogSegmentName, 0));
    checkNonCreationOfIndexSegmentFile(indexSegment,
        new Offset(prevLogSegmentName, indexSegment.getStartOffset().getOffset()));
    checkNonCreationOfIndexSegmentFile(indexSegment, new Offset(logSegmentName, 0));
    List<MockId> shouldBeFound = new ArrayList<>();
    List<MockId> shouldNotBeFound = new ArrayList<>(Arrays.asList(id3, id2, id1));
    for (int safeEndPoint = 1000; safeEndPoint <= 3000; safeEndPoint += 1000) {
      shouldBeFound.add(shouldNotBeFound.remove(0));
      // repeat twice
      for (int i = 0; i < 2; i++) {
        indexSegment.writeIndexSegmentToFile(new Offset(logSegmentName, safeEndPoint));
        Journal journal = new Journal(tempDir.getAbsolutePath(), 3, 3);
        IndexSegment fromDisk =
            new IndexSegment(indexSegment.getFile(), false, STORE_KEY_FACTORY, STORE_CONFIG, metrics, journal, time);
        assertEquals("Number of items incorrect", shouldBeFound.size(), fromDisk.getNumberOfItems());
        for (MockId id : shouldBeFound) {
          verifyValues(fromDisk, id, 1, false);
        }
        for (MockId id : shouldNotBeFound) {
          assertNull("Values for key should not have been found", fromDisk.find(id));
        }
      }
    }
    // now persist the delete too
    indexSegment.writeIndexSegmentToFile(new Offset(logSegmentName, 3100));
    Journal journal = new Journal(tempDir.getAbsolutePath(), 3, 3);
    IndexSegment fromDisk =
        new IndexSegment(indexSegment.getFile(), false, STORE_KEY_FACTORY, STORE_CONFIG, metrics, journal, time);
    assertEquals("Number of items incorrect", 4, fromDisk.getNumberOfItems());
    for (MockId id : new MockId[]{id1, id2, id3}) {
      verifyValues(fromDisk, id, id.equals(id2) ? 2 : 1, id.equals(id2));
    }
  }

  /**
   * Tests some corner cases with
   * {@link IndexSegment#getIndexEntriesSince(StoreKey, FindEntriesCondition, List, AtomicLong, boolean)}
   * - tests that all values of a key are returned even if the find entries condition max size expires when the first
   * value is loaded
   * @throws StoreException
   */
  @Test
  public void getIndexEntriesCornerCasesTest() throws IOException, StoreException {
    String logSegmentName = LogSegmentNameHelper.getName(0, 0);
    MockId id1 = new MockId("0" + UtilsTest.getRandomString(CUSTOM_ID_SIZE - 1));
    MockId id2 = new MockId("1" + UtilsTest.getRandomString(CUSTOM_ID_SIZE - 1));
    MockId id3 = new MockId("2" + UtilsTest.getRandomString(CUSTOM_ID_SIZE - 1));
    short accountId = Utils.getRandomShort(TestUtils.RANDOM);
    short containerId = Utils.getRandomShort(TestUtils.RANDOM);
    IndexValue value1 =
        IndexValueTest.getIndexValue(1000, new Offset(logSegmentName, 0), Utils.Infinite_Time, time.milliseconds(),
            accountId, containerId, version);
    IndexValue value2 =
        IndexValueTest.getIndexValue(1000, new Offset(logSegmentName, 1000), Utils.Infinite_Time, time.milliseconds(),
            accountId, containerId, version);
    IndexValue value3 =
        IndexValueTest.getIndexValue(1000, new Offset(logSegmentName, 2000), Utils.Infinite_Time, time.milliseconds(),
            accountId, containerId, version);
    time.sleep(TimeUnit.SECONDS.toMillis(1));
    // generate a DELETE
    IndexValue delValue2 =
        IndexValueTest.getIndexValue(value2.getSize(), value2.getOffset(), value2.getExpiresAtMs(), time.milliseconds(),
            value2.getAccountId(), value2.getContainerId(), version);
    delValue2.setNewOffset(new Offset(logSegmentName, 3000));
    delValue2.setNewSize(100);
    delValue2.setFlag(IndexValue.Flags.Delete_Index);
    IndexSegment indexSegment = generateIndexSegment(new Offset(logSegmentName, 0));
    // inserting in the opposite order by design to ensure that writes are based on offset ordering and not key ordering
    indexSegment.addEntry(new IndexEntry(id3, value1), new Offset(logSegmentName, 1000));
    indexSegment.addEntry(new IndexEntry(id2, value2), new Offset(logSegmentName, 2000));
    indexSegment.addEntry(new IndexEntry(id1, value3), new Offset(logSegmentName, 3000));
    indexSegment.addEntry(new IndexEntry(id2, delValue2), new Offset(logSegmentName, 3100));

    indexSegment.writeIndexSegmentToFile(new Offset(logSegmentName, 3100));
    indexSegment.map(true);
    List<IndexEntry> entries = new ArrayList<>();
    for (boolean map : new boolean[]{false, true}) {
      Journal journal = new Journal(tempDir.getAbsolutePath(), 3, 3);
      IndexSegment fromDisk =
          new IndexSegment(indexSegment.getFile(), map, STORE_KEY_FACTORY, STORE_CONFIG, metrics, journal, time);
      // getIndexEntriesSince with maxSize = 0 should not return anything
      FindEntriesCondition condition = new FindEntriesCondition(0);
      assertFalse("getIndexEntriesSince() should not return anything",
          fromDisk.getIndexEntriesSince(null, condition, entries, new AtomicLong(0), false));
      assertEquals("There should be no entries returned", 0, entries.size());
      // getIndexEntriesSince with maxSize <= 1000 should return only the first key (id1)
      condition = new FindEntriesCondition(1000);
      assertTrue("getIndexEntriesSince() should return one entry",
          fromDisk.getIndexEntriesSince(null, condition, entries, new AtomicLong(0), false));
      assertEquals("There should be one entry returned", 1, entries.size());
      assertEquals("Key in entry is incorrect", id1, entries.get(0).getKey());
      assertEquals("Value in entry is incorrect", value3.getBytes(), entries.get(0).getValue().getBytes());
      entries.clear();
      // getIndexEntriesSince with maxSize > 1000 and <= 2100 should return three entries
      for (int maxSize : new int[]{1001, 2100}) {
        condition = new FindEntriesCondition(maxSize);
        assertTrue("getIndexEntriesSince() should return entries",
            fromDisk.getIndexEntriesSince(null, condition, entries, new AtomicLong(0), false));
        assertEquals("There should be three entries returned", 3, entries.size());
        assertEquals("Key in entry is incorrect", id1, entries.get(0).getKey());
        assertEquals("Value in entry is incorrect", value3.getBytes(), entries.get(0).getValue().getBytes());
        assertEquals("Key in entry is incorrect", id2, entries.get(1).getKey());
        assertEquals("Value in entry is incorrect", value2.getBytes(), entries.get(1).getValue().getBytes());
        assertEquals("Key in entry is incorrect", id2, entries.get(2).getKey());
        assertEquals("Value in entry is incorrect", delValue2.getBytes(), entries.get(2).getValue().getBytes());
        entries.clear();
      }
      // getIndexEntriesSince with maxSize > 2100 should return four entries
      condition = new FindEntriesCondition(2101);
      assertTrue("getIndexEntriesSince() should return entries",
          fromDisk.getIndexEntriesSince(null, condition, entries, new AtomicLong(0), false));
      assertEquals("There should be four entries returned", 4, entries.size());
      assertEquals("Key in entry is incorrect", id1, entries.get(0).getKey());
      assertEquals("Value in entry is incorrect", value3.getBytes(), entries.get(0).getValue().getBytes());
      assertEquals("Key in entry is incorrect", id2, entries.get(1).getKey());
      assertEquals("Value in entry is incorrect", value2.getBytes(), entries.get(1).getValue().getBytes());
      assertEquals("Key in entry is incorrect", id2, entries.get(2).getKey());
      assertEquals("Value in entry is incorrect", delValue2.getBytes(), entries.get(2).getValue().getBytes());
      assertEquals("Key in entry is incorrect", id3, entries.get(3).getKey());
      assertEquals("Value in entry is incorrect", value1.getBytes(), entries.get(3).getValue().getBytes());
      entries.clear();
      // getIndexEntriesSince with maxSize > 0 and <= 1100 should return two entries
      for (int maxSize : new int[]{1, 1100}) {
        condition = new FindEntriesCondition(maxSize);
        assertTrue("getIndexEntriesSince() should return entries",
            fromDisk.getIndexEntriesSince(id1, condition, entries, new AtomicLong(0), false));
        assertEquals("There should be two entries returned", 2, entries.size());
        assertEquals("Key in entry is incorrect", id2, entries.get(0).getKey());
        assertEquals("Value in entry is incorrect", value2.getBytes(), entries.get(0).getValue().getBytes());
        assertEquals("Key in entry is incorrect", id2, entries.get(1).getKey());
        assertEquals("Value in entry is incorrect", delValue2.getBytes(), entries.get(1).getValue().getBytes());
        entries.clear();
      }
    }
  }

  // helpers
  // comprehensiveTest() helpers

  /**
   * Comprehensive tests for {@link IndexSegment}.
   * 1. Creates a segment and checks the getters to make sure they return the right values
   * 2. Adds some put entries with random sizes, checks getters again and exercises {@link IndexSegment#find(StoreKey)}
   * and {@link IndexSegment#getEntriesSince(StoreKey, FindEntriesCondition, List, AtomicLong)}.
   * 3. Adds some delete entries (deletes some existing put entries and creates deletes for puts not in this segment)
   * and does the same checks as #2.
   * 4. Writes index to a file and loads it mapped and unmapped and does all the checks in #2 once again along with
   * checking that journal entries are populated correctly.
   * @throws IOException
   * @throws StoreException
   */
  private void doComprehensiveTest(short version, boolean includeSmallKeys, boolean includeLargeKeys)
      throws IOException, StoreException {
    String[] logSegmentNames = {LogSegmentNameHelper.generateFirstSegmentName(false), generateRandomLogSegmentName()};
    int valueSize = version == PersistentIndex.VERSION_0 ? IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V0
        : IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V1;
    for (String logSegmentName : logSegmentNames) {
      long writeStartOffset = Utils.getRandomLong(TestUtils.RANDOM, 1000);
      Offset startOffset = new Offset(logSegmentName, writeStartOffset);
      NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex = new TreeMap<>();
      // advance time so that last modified time for VERSION_1 has different last modified times for different index
      // segments
      time.sleep(10 * Time.MsPerSec);
      IndexSegment indexSegment = generateIndexSegment(startOffset);
      Pair<StoreKey, PersistentIndex.IndexEntryType> resetKey = null;
      verifyIndexSegmentDetails(indexSegment, startOffset, 0, 0, false, startOffset.getOffset(), time.milliseconds(),
          null);
      int numItems = 10;
      int numSmallKeys = 0;
      int numLargeKeys = 0;
      List<Long> offsets = new ArrayList<>();
      offsets.add(writeStartOffset);
      for (int i = 0; i < numItems - 1; i++) {
        // size has to be > 0 (no record is 0 sized)
        long size = Utils.getRandomLong(TestUtils.RANDOM, 1000) + 1;
        offsets.add(writeStartOffset + size);
        writeStartOffset += size;
        if (i % 3 == 1 && includeSmallKeys) {
          numSmallKeys++;
        }
        if (i % 3 == 2 && includeLargeKeys) {
          numLargeKeys++;
        }
      }
      long lastEntrySize = Utils.getRandomLong(TestUtils.RANDOM, 1000) + 1;
      long endOffset = offsets.get(offsets.size() - 1) + lastEntrySize;
      List<IndexEntry> newEntries =
          addPutEntries(offsets, lastEntrySize, indexSegment, referenceIndex, includeSmallKeys, includeLargeKeys);
      if (version != PersistentIndex.VERSION_0) {
        resetKey = new Pair<>(newEntries.get(0).getKey(), PersistentIndex.IndexEntryType.PUT);
      }
      int expectedSizeWritten =
          SMALLER_KEY_SIZE * numSmallKeys + LARGER_KEY_SIZE * numLargeKeys + KEY_SIZE * (numItems - numSmallKeys
              - numLargeKeys) + numItems * valueSize;
      verifyIndexSegmentDetails(indexSegment, startOffset, numItems, expectedSizeWritten, false, endOffset,
          time.seconds() * Time.MsPerSec, resetKey);
      verifyFind(referenceIndex, indexSegment);
      verifyGetEntriesSince(referenceIndex, indexSegment);

      int extraIdsToDelete = 10;
      Set<MockId> idsToDelete = getIdsToDelete(referenceIndex, extraIdsToDelete);
      addDeleteEntries(idsToDelete, indexSegment, referenceIndex);
      endOffset += idsToDelete.size() * DELETE_FILE_SPAN_SIZE;
      numItems += idsToDelete.size();
      for (MockId id : idsToDelete) {
        expectedSizeWritten += valueSize + id.sizeInBytes();
      }
      verifyIndexSegmentDetails(indexSegment, startOffset, numItems, expectedSizeWritten, false, endOffset,
          time.milliseconds(), resetKey);
      verifyFind(referenceIndex, indexSegment);
      verifyGetEntriesSince(referenceIndex, indexSegment);
      indexSegment.writeIndexSegmentToFile(indexSegment.getEndOffset());
      verifyReadFromFile(referenceIndex, indexSegment.getFile(), startOffset, numItems, expectedSizeWritten, endOffset,
          time.milliseconds(), resetKey);
    }
  }

  /**
   * @return a random log segment name.
   */
  private String generateRandomLogSegmentName() {
    long pos = Utils.getRandomLong(TestUtils.RANDOM, 1000);
    long gen = Utils.getRandomLong(TestUtils.RANDOM, 1000);
    return LogSegmentNameHelper.getName(pos, gen);
  }

  /**
   * Generates an {@link IndexSegment} for entries from {@code startOffset}.
   * @param startOffset the start {@link Offset} of the {@link IndexSegment}.
   * @return an {@link IndexSegment} for entries from {@code startOffset}.
   */
  private IndexSegment generateIndexSegment(Offset startOffset) {
    IndexSegment indexSegment;
    if (version == PersistentIndex.VERSION_0) {
      indexSegment = new MockIndexSegmentV0(tempDir.getAbsolutePath(), startOffset, STORE_KEY_FACTORY,
          KEY_SIZE + IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V0, IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V0, STORE_CONFIG,
          metrics, time);
    } else if (version == PersistentIndex.VERSION_1) {
      indexSegment = new MockIndexSegmentV1(tempDir.getAbsolutePath(), startOffset, STORE_KEY_FACTORY,
          KEY_SIZE + IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V1, IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V1, STORE_CONFIG,
          metrics, time);
    } else {
      indexSegment = new IndexSegment(tempDir.getAbsolutePath(), startOffset, STORE_KEY_FACTORY,
          KEY_SIZE + IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V1, IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V1, STORE_CONFIG,
          metrics, time);
    }
    return indexSegment;
  }

  /**
   * Creates an {@link IndexSegment} from the given {@code file}.
   * @param file the {@link File} to use to build the {@link IndexSegment}.
   * @param isMapped {@code true} if the segment needs to be mmapped, {@code false} for holding entries in memory.
   * @param journal the {@link Journal} to use.
   * @return an {@link IndexSegment} from the given {@code file}.
   * @throws StoreException
   */
  private IndexSegment createIndexSegmentFromFile(File file, boolean isMapped, Journal journal) throws StoreException {
    return new IndexSegment(file, isMapped, STORE_KEY_FACTORY, STORE_CONFIG, metrics, journal, time);
  }

  /**
   * Adds put entries at offsets {@code offsets} into {@code segment}. The {@code offsets} are assumed to be contiguous
   * with no breaks.
   * @param offsets the offsets to add the entries at. Difference b/w two entries will be used to compute size.
   * @param lastEntrySize the size of the last entry in {@code offsets}.
   * @param segment the {@link IndexSegment} to add the entries to.
   * @param referenceIndex the {@link NavigableMap} to add all the entries to. This represents the source of truth for
   *                       all checks.
   * @param includeSmallKeys if true, entries that are added will include small keys.
   * @param includeLargeKeys if true, entries that are added will include large keys.
   * @return {@link List} of {@link IndexEntry}s that were added to the {@link IndexSegment}
   * @throws StoreException
   */
  private List<IndexEntry> addPutEntries(List<Long> offsets, long lastEntrySize, IndexSegment segment,
      NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex, boolean includeSmallKeys, boolean includeLargeKeys)
      throws StoreException {
    List<IndexEntry> addedEntries = new ArrayList<>();
    for (int i = 0; i < offsets.size(); i++) {
      MockId id;
      do {
        if (includeSmallKeys && i % 3 == 1) {
          id = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE / 2));
        } else if (includeLargeKeys && i % 3 == 2) {
          id = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE + CUSTOM_ID_SIZE / 2));
        } else {
          id = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE));
        }
      } while (referenceIndex.containsKey(id));
      long offset = offsets.get(i);
      long size = i == offsets.size() - 1 ? lastEntrySize : offsets.get(i + 1) - offset;
      IndexValue value =
          IndexValueTest.getIndexValue(size, new Offset(segment.getLogSegmentName(), offset), Utils.Infinite_Time,
              time.milliseconds(), Utils.getRandomShort(TestUtils.RANDOM), Utils.getRandomShort(TestUtils.RANDOM),
              version);
      IndexEntry entry = new IndexEntry(id, value);
      segment.addEntry(entry, new Offset(segment.getLogSegmentName(), offset + size));
      addedEntries.add(entry);
      referenceIndex.computeIfAbsent(id, k -> new TreeSet<>()).add(value);
    }
    return addedEntries;
  }

  /**
   * Verifies the getters of the {@link IndexSegment} and makes sure the values returned by them match the reference
   * values.
   * @param indexSegment the {@link IndexSegment} to test.
   * @param startOffset the expected start {@link Offset} of the {@code indexSegment}
   * @param numItems the expected number of items the {@code indexSegment}
   * @param sizeWritten the expected number of bytes written to the {@code indexSegment}
   * @param isMapped the expected mapped state of the {@code indexSegment}
   * @param endOffset the expected end offset of the {@code indexSegment}
   * @param lastModifiedTimeInMs the last modified time in ms
   * @param resetKey the reset key for the index segment
   */
  private void verifyIndexSegmentDetails(IndexSegment indexSegment, Offset startOffset, int numItems, int sizeWritten,
      boolean isMapped, long endOffset, long lastModifiedTimeInMs,
      Pair<StoreKey, PersistentIndex.IndexEntryType> resetKey) {
    String logSegmentName = startOffset.getName();
    long valueSize = version == PersistentIndex.VERSION_0 ? IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V0
        : IndexValue.INDEX_VALUE_SIZE_IN_BYTES_V1;
    long indexPersistedEntrySize = indexSegment.getPersistedEntrySize();
    assertEquals("LogSegment name not as expected", logSegmentName, indexSegment.getLogSegmentName());
    assertEquals("Start offset not as expected", startOffset, indexSegment.getStartOffset());
    assertEquals("End offset not as expected", new Offset(logSegmentName, endOffset), indexSegment.getEndOffset());
    assertEquals("Mapped state is incorrect", isMapped, indexSegment.isMapped());
    assertEquals("Entry size is incorrect: " + version, indexPersistedEntrySize, indexSegment.getPersistedEntrySize());
    assertEquals("Value size is incorrect", valueSize, indexSegment.getValueSize());
    assertEquals("Reset key mismatch ", resetKey, indexSegment.getResetKey());
    if (version != PersistentIndex.VERSION_0) {
      assertEquals("Last modified time is incorrect", lastModifiedTimeInMs, indexSegment.getLastModifiedTimeMs());
    }
    // incase of version 0, last modified time is calculated based on SystemTime and hence cannot verify for equivalency
    if (!isMapped) {
      assertEquals("Size written not as expected", sizeWritten, indexSegment.getSizeWritten());
      assertEquals("Number of items not as expected", numItems, indexSegment.getNumberOfItems());
    }

    String expectedFilename =
        startOffset.getOffset() + BlobStore.SEPARATOR + IndexSegment.INDEX_SEGMENT_FILE_NAME_SUFFIX;
    if (!logSegmentName.isEmpty()) {
      expectedFilename = logSegmentName + BlobStore.SEPARATOR + expectedFilename;
    }
    String path = tempDir.getAbsolutePath() + File.separator + expectedFilename;
    assertEquals("File path not as expected", path, indexSegment.getFile().getAbsolutePath());
    assertEquals("Segment start offset from filename not as expected", startOffset,
        IndexSegment.getIndexSegmentStartOffset(indexSegment.getFile().getName()));
  }

  /**
   * Verifies {@link IndexSegment#find(StoreKey)} to make sure that it returns/does not return values.
   * @param referenceIndex the index entries to be used as reference.
   * @param segment the {@link IndexSegment} to test
   * @throws StoreException
   */
  private void verifyFind(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex, IndexSegment segment)
      throws StoreException {
    for (Map.Entry<MockId, NavigableSet<IndexValue>> entry : referenceIndex.entrySet()) {
      NavigableSet<IndexValue> referenceValues = entry.getValue();
      NavigableSet<IndexValue> values = segment.find(entry.getKey());
      assertNotNull("Values obtained from segment is null", values);
      IndexValue valueFromSegment = values.first();
      for (IndexValue referenceValue : referenceValues) {
        assertEquals("Offset is not equal", referenceValue.getOffset(), valueFromSegment.getOffset());
        assertEquals("Value is not equal", referenceValue.getBytes(), valueFromSegment.getBytes());
        valueFromSegment = values.higher(valueFromSegment);
      }
      assertNull("There should be no more values in the segment", valueFromSegment);
    }
    // try to find a key that does not exist.
    MockId id = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE));
    assertNull("Should have failed to find non existent key", segment.find(id));
  }

  /**
   * Verifies {@link IndexSegment#getEntriesSince(StoreKey, FindEntriesCondition, List, AtomicLong)} to make sure that
   * it returns the right values for all keys in {@code referenceIndex} and for all conditions.
   * @param referenceIndex the index entries to be used as reference.
   * @param segment the {@link IndexSegment} to test
   * @throws IOException
   */
  private void verifyGetEntriesSince(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex,
      IndexSegment segment) throws IOException {
    // index segment is "too" recent
    FindEntriesCondition condition = new FindEntriesCondition(Long.MAX_VALUE, segment.getLastModifiedTimeSecs() - 1);
    List<MessageInfo> entries = new ArrayList<>();
    assertFalse("Should not have fetched entries since segment is too recent",
        segment.getEntriesSince(null, condition, entries, new AtomicLong(0)));
    assertEquals("Should not have fetched entries since segment is too recent", 0, entries.size());

    long sizeLeftInSegment = segment.getEndOffset().getOffset() - segment.getStartOffset().getOffset();
    getEntriesSinceTest(referenceIndex, segment, null, sizeLeftInSegment);
    for (Map.Entry<MockId, NavigableSet<IndexValue>> entry : referenceIndex.entrySet()) {
      for (IndexValue value : entry.getValue()) {
        sizeLeftInSegment -= value.getSize();
      }
      getEntriesSinceTest(referenceIndex, segment, entry.getKey(), sizeLeftInSegment);
    }
  }

  /**
   * Verifies {@link IndexSegment#getEntriesSince(StoreKey, FindEntriesCondition, List, AtomicLong)} to make sure that
   * it returns the right values for {@code idToCheck} for all size conditions.
   * @param referenceIndex the index entries to be used as reference.
   * @param segment the {@link IndexSegment} to test
   * @param idToCheck the {@link MockId} to use as input for getEntriesSince().
   * @param sizeLeftInSegment the total size of values in the segment beyond {@code idToCheck}.
   * @throws IOException
   */
  private void getEntriesSinceTest(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex, IndexSegment segment,
      MockId idToCheck, long sizeLeftInSegment) throws IOException {
    long maxSize = 0;
    MockId idHigherThanIdToCheck = idToCheck == null ? referenceIndex.firstKey() : referenceIndex.higherKey(idToCheck);
    MockId highestIdIncluded = null;
    while (maxSize <= sizeLeftInSegment) {
      MockId nextHighestIdIncluded =
          highestIdIncluded == null ? idHigherThanIdToCheck : referenceIndex.higherKey(highestIdIncluded);
      long existingSize = 0;
      while (existingSize < maxSize) {
        doGetEntriesSinceTest(referenceIndex, segment, idToCheck, maxSize, existingSize, highestIdIncluded);
        for (IndexValue value : referenceIndex.get(highestIdIncluded)) {
          existingSize += value.getSize();
        }
        highestIdIncluded = referenceIndex.lowerKey(highestIdIncluded);
      }
      doGetEntriesSinceTest(referenceIndex, segment, idToCheck, maxSize, maxSize, null);
      if (nextHighestIdIncluded != null) {
        highestIdIncluded = nextHighestIdIncluded;
        for (IndexValue value : referenceIndex.get(highestIdIncluded)) {
          maxSize += value.getSize();
        }
      } else {
        break;
      }
    }
    highestIdIncluded = referenceIndex.lastKey().equals(idToCheck) ? null : referenceIndex.lastKey();
    // check the case where maxSize is more than the number of entries in the segment
    doGetEntriesSinceTest(referenceIndex, segment, idToCheck, maxSize + 1, 0, highestIdIncluded);
    // try to getEntriesSince a key that does not exist.
    MockId id = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE));
    doGetEntriesSinceTest(referenceIndex, segment, id, Long.MAX_VALUE, 0, null);
  }

  /**
   * Does the {@link IndexSegment#getEntriesSince(StoreKey, FindEntriesCondition, List, AtomicLong)} and checks that
   * all the returned entries are as expected and that all the entries expected have been returned.
   * @param referenceIndex the index entries to be used as reference.
   * @param segment the {@link IndexSegment} to test
   * @param idToCheck the {@link MockId} to check.
   * @param maxSize the parameter for {@link FindEntriesCondition#FindEntriesCondition(long)}.
   * @param existingSize the third parameter for getEntriesSince().
   * @param highestExpectedId the highest expected Id in the returned entries.
   * @throws IOException
   */
  private void doGetEntriesSinceTest(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex,
      IndexSegment segment, MockId idToCheck, long maxSize, long existingSize, MockId highestExpectedId)
      throws IOException {
    // test getEntriesSince
    FindEntriesCondition condition = new FindEntriesCondition(maxSize);

    List<MessageInfo> entries = new ArrayList<>();
    assertEquals("Unexpected return value from getEntriesSince()", highestExpectedId != null,
        segment.getEntriesSince(idToCheck, condition, entries, new AtomicLong(existingSize)));
    if (highestExpectedId != null) {
      assertEquals("Highest ID not as expected", highestExpectedId, entries.get(entries.size() - 1).getStoreKey());
      MockId nextExpectedId = idToCheck == null ? referenceIndex.firstKey() : referenceIndex.higherKey(idToCheck);
      for (MessageInfo info : entries) {
        assertEquals("Entry is unexpected", nextExpectedId, info.getStoreKey());
        nextExpectedId = referenceIndex.higherKey(nextExpectedId);
      }
    } else {
      assertEquals("Entries list is not empty", 0, entries.size());
    }

    // test getIndexEntriesSince()
    for (boolean oneEntryPerKey : new boolean[]{true, false}) {
      List<IndexEntry> indexEntries = new ArrayList<>();
      assertEquals("Unexpected return value from getIndexEntriesSince()", highestExpectedId != null,
          segment.getIndexEntriesSince(idToCheck, condition, indexEntries, new AtomicLong(existingSize),
              oneEntryPerKey));
      if (highestExpectedId != null) {
        assertEquals("Highest ID not as expected", highestExpectedId,
            indexEntries.get(indexEntries.size() - 1).getKey());
        MockId nextExpectedId = idToCheck == null ? referenceIndex.firstKey() : referenceIndex.higherKey(idToCheck);
        // gather all index entries that should be there
        final List<IndexEntry> expectedEntries = new ArrayList<>();
        while (nextExpectedId != null) {
          NavigableSet<IndexValue> values = referenceIndex.get(nextExpectedId);
          if (oneEntryPerKey) {
            expectedEntries.add(new IndexEntry(nextExpectedId, values.last()));
          } else {
            for (IndexValue value : values) {
              expectedEntries.add(new IndexEntry(nextExpectedId, value));
            }
          }
          if (nextExpectedId.equals(highestExpectedId)) {
            break;
          }
          nextExpectedId = referenceIndex.higherKey(nextExpectedId);
        }
        assertEquals("Number of entries not as expected", expectedEntries.size(), indexEntries.size());
        Iterator<IndexEntry> it = indexEntries.iterator();
        for (IndexEntry expected : expectedEntries) {
          assertTrue("There should be more entries", it.hasNext());
          IndexEntry actual = it.next();
          assertEquals("Key not as expected", expected.getKey(), actual.getKey());
          assertEquals("Value not as expected", expected.getValue().getBytes(), actual.getValue().getBytes());
        }
      } else {
        assertEquals("Entries list is not empty", 0, indexEntries.size());
      }
    }
  }

  /**
   * Gets some IDs for deleting. Picks alternate IDs from {@code referenceIndex} and randomly generates
   * {@code outOfSegmentIdCount} ids.
   * @param referenceIndex the index entries to pick for delete from.
   * @param outOfSegmentIdCount the number of ids to be generated that are not in {@code referenceIndex}.
   * @return a {@link Set} of IDs to create delete entries for.
   */
  private Set<MockId> getIdsToDelete(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex,
      int outOfSegmentIdCount) {
    Set<MockId> idsToDelete = new HashSet<>();
    // return every alternate id in the map
    boolean include = true;
    for (MockId id : referenceIndex.keySet()) {
      if (include) {
        idsToDelete.add(id);
      }
      include = !include;
    }
    // generate some ids for delete
    for (int i = 0; i < outOfSegmentIdCount; i++) {
      MockId id;
      do {
        id = new MockId(UtilsTest.getRandomString(CUSTOM_ID_SIZE));
      } while (referenceIndex.containsKey(id) || idsToDelete.contains(id));
      idsToDelete.add(id);
    }
    return idsToDelete;
  }

  /**
   * Adds delete entries to {@code segment.}
   * @param idsToDelete the {@link Set} of IDs to create delete entries for.
   * @param segment the {@link IndexSegment} to add the entries to.
   * @param referenceIndex the {@link NavigableMap} to add all the entries to. This repreents the source of truth for
   *                       all checks.
   * @throws StoreException
   */
  private void addDeleteEntries(Set<MockId> idsToDelete, IndexSegment segment,
      NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex) throws StoreException {
    for (MockId id : idsToDelete) {
      Offset offset = segment.getEndOffset();
      NavigableSet<IndexValue> values = segment.find(id);
      IndexValue value;
      if (values == null) {
        // create an index value with a random log segment name
        value = IndexValueTest.getIndexValue(1, new Offset(UtilsTest.getRandomString(1), 0), Utils.Infinite_Time,
            time.milliseconds(), Utils.getRandomShort(TestUtils.RANDOM), Utils.getRandomShort(TestUtils.RANDOM),
            version);
      } else if (values.last().isFlagSet(IndexValue.Flags.Delete_Index)) {
        throw new IllegalArgumentException(id + " is already deleted");
      } else {
        value = values.last();
      }
      IndexValue newValue = IndexValueTest.getIndexValue(value, version);
      newValue.setFlag(IndexValue.Flags.Delete_Index);
      newValue.setNewOffset(offset);
      newValue.setNewSize(DELETE_FILE_SPAN_SIZE);
      segment.addEntry(new IndexEntry(id, newValue),
          new Offset(offset.getName(), offset.getOffset() + DELETE_FILE_SPAN_SIZE));
      referenceIndex.computeIfAbsent(id, k -> new TreeSet<>()).add(newValue);
    }
  }

  /**
   * Creates an {@link IndexSegment} from the given {@code file} and checks for both sanity and find operations.
   * @param referenceIndex the index entries to be used as reference.
   * @param file the {@link File} to be used to load the index.
   * @param startOffset the expected start {@link Offset} of the {@link IndexSegment}
   * @param numItems the expected number of items the {@code indexSegment}
   * @param expectedSizeWritten the expected number of bytes written to the {@code indexSegment}
   * @param endOffset the expected end offset of the {@code indexSegment}
   * @param lastModifiedTimeInMs the last modified time of the index segment in ms
   * @param resetKey the resetKey of the index segment
   * @throws IOException
   * @throws StoreException
   */
  private void verifyReadFromFile(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex, File file,
      Offset startOffset, int numItems, int expectedSizeWritten, long endOffset, long lastModifiedTimeInMs,
      Pair<StoreKey, PersistentIndex.IndexEntryType> resetKey) throws IOException, StoreException {
    // read from file (unmapped) and verify that everything is ok
    Journal journal = new Journal(tempDir.getAbsolutePath(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    IndexSegment fromDisk = createIndexSegmentFromFile(file, false, journal);
    verifyIndexSegmentDetails(fromDisk, startOffset, numItems, expectedSizeWritten, false, endOffset,
        lastModifiedTimeInMs, resetKey);
    verifyFind(referenceIndex, fromDisk);
    verifyGetEntriesSince(referenceIndex, fromDisk);
    // journal should contain all the entries
    verifyJournal(referenceIndex, journal);
    fromDisk.map(true);

    // read from file (mapped) and verify that everything is ok
    journal = new Journal(tempDir.getAbsolutePath(), Integer.MAX_VALUE, Integer.MAX_VALUE);
    fromDisk = createIndexSegmentFromFile(file, true, journal);
    verifyIndexSegmentDetails(fromDisk, startOffset, numItems, expectedSizeWritten, true, endOffset,
        lastModifiedTimeInMs, resetKey);
    verifyFind(referenceIndex, fromDisk);
    verifyGetEntriesSince(referenceIndex, fromDisk);
    // journal should not contain any entries
    assertNull("Journal should not have any entries", journal.getFirstOffset());
  }

  /**
   * Verfies that the journal has all and only expected entries.
   * @param referenceIndex the index entries to be used as reference.
   * @param journal the {@link Journal} to check.
   */
  private void verifyJournal(NavigableMap<MockId, NavigableSet<IndexValue>> referenceIndex, Journal journal) {
    // order all available IndexValue by offset
    final TreeMap<Offset, MockId> allIdsByOffset = new TreeMap<>();
    referenceIndex.forEach(
        (key, value) -> value.forEach(indexValue -> allIdsByOffset.put(indexValue.getOffset(), key)));
    List<JournalEntry> entries = journal.getEntriesSince(allIdsByOffset.firstKey(), true);
    assertEquals("Size of entries returned from journal not as expected", allIdsByOffset.size(), entries.size());
    for (JournalEntry entry : entries) {
      StoreKey key = entry.getKey();
      Offset offset = entry.getOffset();
      assertEquals("Key not as expected", key, allIdsByOffset.get(offset));
    }
  }

  // partialWriteTest() helpers

  /**
   * Checks that an index segment file is not created
   * @param indexSegment the index segment to write to file
   * @param safeEndPoint the safe end point to use to the call to {@link IndexSegment#writeIndexSegmentToFile(Offset)}.
   * @throws IOException
   * @throws StoreException
   */
  private void checkNonCreationOfIndexSegmentFile(IndexSegment indexSegment, Offset safeEndPoint)
      throws IOException, StoreException {
    indexSegment.writeIndexSegmentToFile(safeEndPoint);
    assertFalse("Index file should not have been created", indexSegment.getFile().exists());
  }

  /**
   * Verifies that the values obtained for {@code id} from {@code segment} satisfy the count and deleted state as
   * provided
   * @param segment the {@link IndexSegment} to check
   * @param id the {@link MockId} to find values for
   * @param valueCount the number of values expected to be returned
   * @param isDeleted the expected state of the latest value
   * @throws StoreException
   */
  private void verifyValues(IndexSegment segment, MockId id, int valueCount, boolean isDeleted) throws StoreException {
    NavigableSet<IndexValue> values = segment.find(id);
    assertNotNull("Values should have been found for " + id, values);
    assertEquals("Unexpected number of values for " + id, valueCount, values.size());
    assertEquals("Delete flag not as expected for " + id, isDeleted,
        values.last().isFlagSet(IndexValue.Flags.Delete_Index));
  }
}

/**
 * Mock {@link IndexSegment} that uses version {@link PersistentIndex#VERSION_0}
 */
class MockIndexSegmentV0 extends IndexSegment {
  private final int persistedEntrySizeV0;

  MockIndexSegmentV0(String dataDir, Offset startOffset, StoreKeyFactory factory, int entrySize, int valueSize,
      StoreConfig config, StoreMetrics metrics, Time time) {
    super(dataDir, startOffset, factory, entrySize, valueSize, config, metrics, time);
    persistedEntrySizeV0 = entrySize;
  }

  @Override
  Pair<StoreKey, PersistentIndex.IndexEntryType> getResetKey() {
    return null;
  }

  @Override
  short getVersion() {
    return PersistentIndex.VERSION_0;
  }

  @Override
  int getPersistedEntrySize() {
    return persistedEntrySizeV0;
  }
}

/**
 * Mock {@link IndexSegment} that uses version {@link PersistentIndex#VERSION_1}
 */
class MockIndexSegmentV1 extends IndexSegment {
  private final int persistedEntrySizeV1;

  MockIndexSegmentV1(String dataDir, Offset startOffset, StoreKeyFactory factory, int entrySize, int valueSize,
      StoreConfig config, StoreMetrics metrics, Time time) {
    super(dataDir, startOffset, factory, entrySize, valueSize, config, metrics, time);
    persistedEntrySizeV1 = entrySize;
  }

  @Override
  short getVersion() {
    return PersistentIndex.VERSION_1;
  }

  @Override
  int getPersistedEntrySize() {
    return persistedEntrySizeV1;
  }
}

