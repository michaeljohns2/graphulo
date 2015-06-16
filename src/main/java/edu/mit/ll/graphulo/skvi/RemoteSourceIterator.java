package edu.mit.ll.graphulo.skvi;

import edu.mit.ll.graphulo.util.GraphuloUtil;
import edu.mit.ll.graphulo.util.PeekingIterator1;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.ClientConfiguration;
import org.apache.accumulo.core.client.ClientSideIteratorScanner;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.OptionDescriber;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Reads from a remote Accumulo table.
 */
public class RemoteSourceIterator implements SortedKeyValueIterator<Key, Value>, OptionDescriber {
  private static final Logger log = LogManager.getLogger(RemoteSourceIterator.class);

  private String instanceName;
  private String tableName;
  private String zookeeperHost;
  private String username;
  private AuthenticationToken auth;
  /**
   * Zookeeper timeout in milliseconds
   */
  private int timeout = -1;

  private boolean doWholeRow = false,
      doClientSideIterators = false;
  private SortedSet<Range> rowRanges = new TreeSet<>(Collections.singleton(new Range()));
  /**
   * The range given by seek. Clip to this range.
   */
  private Range seekRange;

  /**
   * Holds the current range we are scanning.
   * Goes through the part of ranges after seeking to the beginning of the seek() clip.
   */
  private Iterator<Range> rowRangeIterator;
  private String colFilter = "";

  /**
   * Created in init().
   */
  private Scanner scanner;
  /**
   * Buffers one entry from the remote table.
   */
  private PeekingIterator1<Map.Entry<Key, Value>> remoteIterator;

  /**
   * Call init() after construction.
   */
  public RemoteSourceIterator() {
  }

  /**
   * Copies configuration from other, including connector,
   * EXCEPT creates a new, separate scanner.
   * No need to call init().
   */
  RemoteSourceIterator(RemoteSourceIterator other) {
    other.instanceName = instanceName;
    other.tableName = tableName;
    other.zookeeperHost = zookeeperHost;
    other.username = username;
    other.auth = auth;
    other.timeout = timeout;
    other.doWholeRow = doWholeRow;
    other.rowRanges = rowRanges;
    other.doClientSideIterators = doClientSideIterators;
    other.colFilter = colFilter;
    other.setupConnectorScanner();
  }

  static final IteratorOptions iteratorOptions;

  static {
    Map<String, String> optDesc = new LinkedHashMap<>();
    optDesc.put("zookeeperHost", "address and port");
    optDesc.put("timeout", "Zookeeper timeout between 1000 and 300000 (default 1000)");
    optDesc.put("instanceName", "");
    optDesc.put("tableName", "");
    optDesc.put("username", "");
    optDesc.put("password", "(Anyone who can read the Accumulo table config OR the log files will see your password in plaintext.)");
    optDesc.put("doWholeRow", "Apply WholeRowIterator to remote table scan? (default no)");
    optDesc.put("doClientSideIterators", "Use a ClientSideIteratorScanner? (default no)");
    optDesc.put("rowRanges", "Row ranges to scan for remote Accumulo table, Matlab syntax. (default ':,' all)");
    optDesc.put("colFilter", "Range on column qualifiers, e.g. 'a,b,c,' (default blank for all). More efficient with simpler filters.");
    iteratorOptions = new IteratorOptions("RemoteSourceIterator",
        "Reads from a remote Accumulo table. Replaces parent iterator with the remote table.",
        Collections.unmodifiableMap(optDesc), null);
  }

  @Override
  public IteratorOptions describeOptions() {
    return iteratorOptions;
  }

  @Override
  public boolean validateOptions(Map<String, String> options) {
    return validateOptionsStatic(options);
  }

  public static boolean validateOptionsStatic(Map<String, String> options) {
    new RemoteSourceIterator().parseOptions(options);
    return true;
  }

  private void parseOptions(Map<String, String> map) {
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (entry.getValue().isEmpty())
        continue;
      switch (entry.getKey()) {
        case "zookeeperHost":
          zookeeperHost = entry.getValue();
          break;
        case "timeout":
          timeout = Integer.parseInt(entry.getValue());
          break;
        case "instanceName":
          instanceName = entry.getValue();
          break;
        case "tableName":
          tableName = entry.getValue();
          break;
        case "username":
          username = entry.getValue();
          break;
        case "password":
          auth = new PasswordToken(entry.getValue());
          break;

        case "doWholeRow":
          doWholeRow = Boolean.parseBoolean(entry.getValue());
          break;
        case "rowRanges":
          rowRanges = parseRanges(entry.getValue());
          break;
        case "colFilter":
          colFilter = entry.getValue(); //GraphuloUtil.d4mRowToTexts(entry.getValue());
          break;
        case "doClientSideIterators":
          doClientSideIterators = Boolean.parseBoolean(entry.getValue());
          break;
        default:
          log.warn("Unrecognized option: " + entry);
          continue;
      }
      log.trace("Option OK: " + entry);
    }
    // Required options
    if (zookeeperHost == null ||
        instanceName == null ||
        tableName == null ||
        username == null ||
        auth == null)
      throw new IllegalArgumentException("not enough options provided");
  }

  /**
   * Parse string s in the Matlab format "row1,row5,row7,:,row9,w,:,z,zz,:,"
   * Does not have to be ordered but cannot overlap.
   *
   * @param s -
   * @return a bunch of ranges
   */
  static SortedSet<Range> parseRanges(String s) {
    Collection<Range> rngs = GraphuloUtil.d4mRowToRanges(s);
    rngs = Range.mergeOverlapping(rngs);
    return new TreeSet<>(rngs);
  }

  @Override
  public void init(SortedKeyValueIterator<Key, Value> source, Map<String, String> map, IteratorEnvironment iteratorEnvironment) throws IOException {
    if (source != null)
      log.warn("RemoteSourceIterator ignores/replaces parent source passed in init(): " + source);

    parseOptions(map);

    setupConnectorScanner();

    log.debug("RemoteSourceIterator on table " + tableName + ": init() succeeded");
  }

  static final Text EMPTY_TEXT = new Text();

  private void setupConnectorScanner() {
    ClientConfiguration cc = ClientConfiguration.loadDefault().withInstance(instanceName).withZkHosts(zookeeperHost);
    if (timeout != -1)
      cc = cc.withZkTimeout(timeout);
    Instance instance = new ZooKeeperInstance(cc);
    Connector connector;
    try {
      connector = instance.getConnector(username, auth);
    } catch (AccumuloException | AccumuloSecurityException e) {
      log.error("failed to connect to Accumulo instance " + instanceName, e);
      throw new RuntimeException(e);
    }

    try {
      scanner = connector.createScanner(tableName, Authorizations.EMPTY);
    } catch (TableNotFoundException e) {
      log.error(tableName + " does not exist in instance " + instanceName, e);
      throw new RuntimeException(e);
    }

    if (doClientSideIterators)
      scanner = new ClientSideIteratorScanner(scanner);

    GraphuloUtil.applyGeneralColumnFilter(colFilter,scanner,4);

    if (doWholeRow) {
      // TODO: make priority dynamic in case 25 is taken; make name dynamic in case iterator name already exists. Or buffer here.
      IteratorSetting iset = new IteratorSetting(25, WholeRowIterator.class);
      scanner.addScanIterator(iset);
    }
  }

  @Override
  protected void finalize() throws Throwable {
    super.finalize();
    scanner.close();
  }

  /**
   * Advance to the first subset range whose end key >= the seek start key.
   */
  public static Iterator<Range> getFirstRangeStarting(PeekingIterator1<Range> iter, Range seekRange) {
    if (!seekRange.isInfiniteStartKey())
      while (iter.hasNext() && !iter.peek().isInfiniteStopKey()
          && ((iter.peek().getEndKey().equals(seekRange.getStartKey()) && !seekRange.isEndKeyInclusive())
          || iter.peek().getEndKey().compareTo(seekRange.getStartKey()) < 0)) {
        iter.next();
      }
    return iter;
  }

  @Override
  public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive) throws IOException {
    log.debug("RemoteSourceIterator on table " + tableName + ": seek(): " + range);
    /** configure Scanner to the first entry to inject after the start of the range.
     Range comparison: infinite start first, then inclusive start, then exclusive start
     {@link org.apache.accumulo.core.data.Range#compareTo(Range)} */
    seekRange = range;
    rowRangeIterator = getFirstRangeStarting(new PeekingIterator1<>(rowRanges.iterator()), range); //rowRanges.tailSet(range).iterator();
    remoteIterator = new PeekingIterator1<>(java.util.Collections.<Map.Entry<Key, Value>>emptyIterator());
    next();
  }

//  /**
//   * Restrict columns fetched to the ones given. Takes effect on next seek().
//   *
//   * @param columns Columns to fetch. Null or empty collection for all columns.
//   * @throws IOException
//   */
//  public void setFetchColumns(Collection<IteratorSetting.Column> columns) throws IOException {
//    scanner.clearColumns();
//    if (columns != null)
//      for (IteratorSetting.Column column : columns) {
//        if (column.getColumnQualifier() == null)    // fetch all columns in this column family
//          scanner.fetchColumnFamily(column.getColumnFamily());
//        else
//          scanner.fetchColumn(column.getColumnFamily(), column.getColumnQualifier());
//      }
//  }


  @Override
  public boolean hasTop() {
    return remoteIterator.hasNext();
  }

  @Override
  public void next() throws IOException {
    if (rowRangeIterator == null || remoteIterator == null)
      throw new IllegalStateException("next() called before seek() b/c rowRangeIterator or remoteIterator not set");
    remoteIterator.next(); // does nothing if there is no next (i.e. hasTop()==false)
    while (!remoteIterator.hasNext() && rowRangeIterator.hasNext()) {
      Range range = rowRangeIterator.next();
      range = range.clip(seekRange, true); // clip to the seek range
      if (range == null) // empty intersection - no more ranges by design
        return;
      scanner.setRange(range);
      remoteIterator = new PeekingIterator1<>(scanner.iterator());
    }
    // either no ranges left and we finished the current scan OR remoteIterator.hasNext()==true
//    if (hasTop())
//      log.trace(tableName + " prepared next entry " + getTopKey() + " ==> "
//          + (doWholeRow ? WholeRowIterator.decodeRow(getTopKey(), getTopValue()) : getTopValue()));
//    else
//      log.trace(tableName + " hasTop() == false");
  }

  @Override
  public Key getTopKey() {
    return remoteIterator.peek().getKey(); // returns null if hasTop()==false
  }

  @Override
  public Value getTopValue() {
    return remoteIterator.peek().getValue();
  }

  @Override
  public RemoteSourceIterator deepCopy(IteratorEnvironment iteratorEnvironment) {
    return new RemoteSourceIterator(this);
  }
}