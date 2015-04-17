package edu.mit.ll.graphulo;

import edu.mit.ll.graphulo.util.AccumuloTestBase;
import edu.mit.ll.graphulo.util.TestUtil;
import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

/**
 * Test RemoteSourceIterator and RemoteMergeIterator.
 */
public class RemoteIteratorTest extends AccumuloTestBase {
  private static final Logger log = LogManager.getLogger(RemoteIteratorTest.class);

  @Test
  public void testSource() throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException, IOException {
    Connector conn = tester.getConnector();

    final String tableName, tableName2;
    {
      String[] names = getUniqueNames(2);
      tableName = names[0];
      tableName2 = names[1];
    }
    {
      Map<Key, Value> input = new HashMap<>();
      input.put(new Key("ccc", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("ddd", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("pogo", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("ddd", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("ggg", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("pogo", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("xyz", "", "cq2"), new Value("8".getBytes()));
      TestUtil.createTestTable(conn, tableName, null, input);
    }
    TestUtil.createTestTable(conn, tableName2);

    List<SortedMap<Key, Value>> expectList = new ArrayList<>();
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("ccc", "", "cq"), new Value("7".getBytes()));
      expectList.add(smap);
    }
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("ddd", "", "cq"), new Value("7".getBytes()));
      smap.put(new Key("ddd", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("ggg", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("pogo", "", "cq"), new Value("7".getBytes()));
      smap.put(new Key("pogo", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("xyz", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }

    Scanner scanner = conn.createScanner(tableName2, Authorizations.EMPTY);
    Map<String, String> itprops = new HashMap<>();
    itprops.put("instanceName", conn.getInstance().getInstanceName());
    itprops.put("tableName", tableName);
    itprops.put("zookeeperHost", conn.getInstance().getZooKeepers());
    //itprops.put("timeout","5000");
    itprops.put("username", tester.getUsername());
    itprops.put("password", new String(tester.getPassword().getPassword()));
    itprops.put("doWholeRow", "true"); // *
    IteratorSetting itset = new IteratorSetting(5, RemoteSourceIterator.class, itprops); //"edu.mit.ll.graphulo.RemoteSourceIterator", itprops);
    scanner.addScanIterator(itset);
//    log.info("Results of scan on table " + tableName2 + " remote to " + tableName + ':');
    Iterator<SortedMap<Key, Value>> expectIter = expectList.iterator();
    for (Map.Entry<Key, Value> entry : scanner) {
      SortedMap<Key, Value> actualMap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      actualMap.putAll(WholeRowIterator.decodeRow(entry.getKey(), entry.getValue()));
      Assert.assertTrue(expectIter.hasNext());
      SortedMap<Key, Value> expectMap = expectIter.next();
      Assert.assertEquals(expectMap, actualMap);
    }
    Assert.assertFalse(expectIter.hasNext());

    conn.tableOperations().delete(tableName);
    conn.tableOperations().delete(tableName2);
  }

  @Test
  public void testSourceSubset() throws TableExistsException, AccumuloSecurityException, AccumuloException, TableNotFoundException, IOException {
    Connector conn = tester.getConnector();

    final String tableName, tableName2;
    {
      String[] names = getUniqueNames(2);
      tableName = names[0];
      tableName2 = names[1];
    }
    {
      Map<Key, Value> input = new HashMap<>();
      input.put(new Key("ccc", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("ddd", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("pogo", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("ddd", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("ggg", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("pogo", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("xyz", "", "cq2"), new Value("8".getBytes()));
      TestUtil.createTestTable(conn, tableName, null, input);
    }
    TestUtil.createTestTable(conn, tableName2);

    List<SortedMap<Key, Value>> expectList = new ArrayList<>();
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("ddd", "", "cq"), new Value("7".getBytes()));
      smap.put(new Key("ddd", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("ggg", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }
    {
      SortedMap<Key, Value> smap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      smap.put(new Key("pogo", "", "cq"), new Value("7".getBytes()));
      smap.put(new Key("pogo", "", "cq2"), new Value("8".getBytes()));
      expectList.add(smap);
    }

    Scanner scanner = conn.createScanner(tableName2, Authorizations.EMPTY);
    Map<String, String> itprops = new HashMap<>();
    itprops.put("instanceName", conn.getInstance().getInstanceName());
    itprops.put("tableName", tableName);
    itprops.put("zookeeperHost", conn.getInstance().getZooKeepers());
    //itprops.put("timeout","5000");
    itprops.put("username", tester.getUsername());
    itprops.put("password", new String(tester.getPassword().getPassword()));
    itprops.put("doWholeRow", "true"); // *
    IteratorSetting itset = new IteratorSetting(5, RemoteSourceIterator.class, itprops); //"edu.mit.ll.graphulo.RemoteSourceIterator", itprops);
    scanner.addScanIterator(itset);

    Range range = new Range("ddd", "xxx");
    scanner.setRange(range);
    Iterator<SortedMap<Key, Value>> expectIter = expectList.iterator();
    for (Map.Entry<Key, Value> entry : scanner) {
      SortedMap<Key, Value> actualMap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
      actualMap.putAll(WholeRowIterator.decodeRow(entry.getKey(), entry.getValue()));
      Assert.assertTrue(expectIter.hasNext());
      SortedMap<Key, Value> expectMap = expectIter.next();
      Assert.assertEquals(expectMap, actualMap);
    }
    Assert.assertFalse(expectIter.hasNext());

    conn.tableOperations().delete(tableName);
    conn.tableOperations().delete(tableName2);
  }

  @Test
  public void testMerge() throws AccumuloSecurityException, AccumuloException, TableNotFoundException, TableExistsException, IOException {
    Connector conn = tester.getConnector();

    final String tableName, tableName2;
    {
      String[] names = getUniqueNames(2);
      tableName = names[0];
      tableName2 = names[1];
    }
    {
      Map<Key, Value> input = new HashMap<>();
      input.put(new Key("ccc", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("ddd", "", "cq"), new Value("7".getBytes()));
      input.put(new Key("pogo", "", "cq"), new Value("7".getBytes()));
      TestUtil.createTestTable(conn, tableName, null, input);
    }
    {
      Map<Key, Value> input = new HashMap<>();
      input.put(new Key("ddd", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("ggg", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("pogo", "", "cq2"), new Value("8".getBytes()));
      input.put(new Key("xyz", "", "cq2"), new Value("8".getBytes()));
      TestUtil.createTestTable(conn, tableName2, null, input);
    }

    SortedMap<Key, Value> expectMap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
    expectMap.put(new Key("ccc", "", "cq"), new Value("7".getBytes()));
    expectMap.put(new Key("ddd", "", "cq"), new Value("7".getBytes()));
    expectMap.put(new Key("ddd", "", "cq2"), new Value("8".getBytes()));
    expectMap.put(new Key("ggg", "", "cq2"), new Value("8".getBytes()));
    expectMap.put(new Key("pogo", "", "cq"), new Value("7".getBytes()));
    expectMap.put(new Key("pogo", "", "cq2"), new Value("8".getBytes()));
    expectMap.put(new Key("xyz", "", "cq2"), new Value("8".getBytes()));

    Scanner scanner = conn.createScanner(tableName2, Authorizations.EMPTY);
    Map<String, String> itprops = new HashMap<>();
    itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator + "instanceName", conn.getInstance().getInstanceName());
    itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator + "tableName", tableName);
    itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator + "zookeeperHost", conn.getInstance().getZooKeepers());
    //itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator+"timeout","5000");
    itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator + "username", tester.getUsername());
    itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator + "password", new String(tester.getPassword().getPassword()));
    //itprops.put(RemoteMergeIterator.PREFIX_RemoteIterator + "doWholeRow", "true"); // *
    IteratorSetting itset = new IteratorSetting(5, RemoteMergeIterator.class, itprops); //"edu.mit.ll.graphulo.RemoteSourceIterator", itprops);
    scanner.addScanIterator(itset);
    SortedMap<Key, Value> actualMap = new TreeMap<>(TestUtil.COMPARE_KEY_TO_COLQ);
    for (Map.Entry<Key, Value> entry : scanner) {
      actualMap.put(entry.getKey(), entry.getValue());
    }
    Assert.assertEquals(expectMap, actualMap);

    conn.tableOperations().delete(tableName);
    conn.tableOperations().delete(tableName2);
  }

}
