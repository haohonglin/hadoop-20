package org.apache.hadoop.hdfs;

import java.io.IOException;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.namenode.AvatarNode;
import org.apache.hadoop.hdfs.server.namenode.FSImageTestUtil.CheckpointTrigger;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.hdfs.util.InjectionHandler;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestAvatarTxIds {
  
  final static Log LOG = LogFactory.getLog(TestAvatarTxIds.class);
  
  private MiniAvatarCluster cluster;
  private Configuration conf;
  private FileSystem fs;
  private Random random = new Random();

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    MiniAvatarCluster.createAndStartZooKeeper();
  }

  public void setUp(String name) throws Exception {
    LOG.info("------------------- test: " + name + " START ----------------");
    conf = new Configuration();
    conf.setBoolean("fs.ha.retrywrites", true);
    conf.setBoolean("fs.checkpoint.enabled", true);
    cluster = new MiniAvatarCluster(conf, 3, true, null, null);
    fs = cluster.getFileSystem();
    // give it a time to complete the first checkpoint
    Thread.sleep(3000);
  }

  @After
  public void tearDown() throws Exception {
    fs.close();
    cluster.shutDown();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    MiniAvatarCluster.shutDownZooKeeper();
  }

  public void createEdits(int nEdits) throws IOException {
    for (int i = 0; i < nEdits / 2; i++) {
      // Create file ends up logging two edits to the edit log, one for create
      // file and one for bumping up the generation stamp
      fs.create(new Path("/" + random.nextInt()));
    }
  }

  public long getCurrentTxId(AvatarNode avatar) {
    return avatar.getFSImage().getEditLog().getCurrentTxId();
  }

  @Test
  public void testBasic() throws Exception {
    setUp("testBasic");
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    
    // SLS + ELS + SLS + 20 edits
    assertEquals(23, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }

  @Test
  public void testWithFailover() throws Exception {
    setUp("testWithFailover");
    // Create edits before failover.
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    
    // SLS + ELS (first checkpoint with no txns) + SLS + 20 edits
    assertEquals(23, getCurrentTxId(primary));

    // Perform failover and restart old primary.
    cluster.failOver(); // shutdown adds ELS (24)
    assertEquals(25, getCurrentTxId(cluster.getPrimaryAvatar(0).avatar));
    
    cluster.restartStandby();

    // Get new instances after failover.
    primary = cluster.getPrimaryAvatar(0).avatar;
    standby = cluster.getStandbyAvatar(0).avatar;
    
    // checkpoint by the new standby adds 2 transactions
    // standby opens the log with SLS (25)
    // new standby checkpoints ELS + SLS

    // Create some more edits and verify.
    createEdits(20);
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    
    assertEquals(47, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }

  @Test
  public void testWithFailoverTxIdMismatchHard() throws Exception {
    setUp("testWithFailoverTxIdMismatchHard");
    // Create edits before failover.
    createEdits(20);
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    
    // SLS + ELS (first checkpoint with no txns) + SLS + 20 edits
    assertEquals(23, getCurrentTxId(primary));

    standby.getFSImage().getEditLog().setLastWrittenTxId(49);
    assertEquals(50, getCurrentTxId(standby));

    // Perform failover and verify it fails.
    try {
      cluster.failOver();
    } catch (IOException e) {
      System.out.println("Expected exception : " + e);
      return;
    }
    fail("Did not throw exception");
  }

  @Test
  public void testDoubleFailover() throws Exception {
    setUp("testDoubleFailover");
    // Create edits before failover.
    createEdits(20);
    
    // 23 (STS ELS for initial checkpoint + 20 edits
    // Perform failover.
    cluster.failOver(); // 25
    cluster.restartStandby(); // 27 after first checkpoint
    createEdits(20); // 47
    
    // Perform second failover.
    cluster.failOver(); // 49
    cluster.restartStandby(); // 51    
    createEdits(20);

    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(71, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }

  @Test
  public void testWithStandbyDead() throws Exception {
    setUp("testWithStandbyDead");
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    createEdits(20); // 23 (initial checkpoint) + 20 edits
    assertEquals(23, getCurrentTxId(primary));
    
    cluster.killStandby();
    createEdits(20); // 43
    assertEquals(43, getCurrentTxId(primary));
    
    cluster.restartStandby(); // 45 (checkpoint)
    Thread.sleep(1000); // for checkpoint
    assertEquals(45, getCurrentTxId(primary));
    
    createEdits(20); // 67

    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(65, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }

  @Test
  public void testWithStandbyDeadAfterFailover() throws Exception {
    setUp("testWithStandbyDeadAfterFailover");
    // initial checkpoint 3
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    
    createEdits(20); //23
    assertEquals(23, getCurrentTxId(primary));
    cluster.failOver(); //25
    primary = cluster.getPrimaryAvatar(0).avatar;
    assertEquals(25, getCurrentTxId(primary));
    createEdits(20); //45
    assertEquals(45, getCurrentTxId(primary));
    cluster.killStandby();
    createEdits(20); //65
    assertEquals(65, getCurrentTxId(primary));
    cluster.restartStandby(); //67
    Thread.sleep(1000);
    assertEquals(67, getCurrentTxId(primary));
       
    createEdits(20);

    assertEquals(87, getCurrentTxId(cluster.getPrimaryAvatar(0).avatar));
    
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(87, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }

  @Test
  public void testWithCheckPoints() throws Exception {
    TestAvatarTxIdsHandler h = new TestAvatarTxIdsHandler();
    InjectionHandler.set(h);
    setUp("testWithCheckPoints");
    // 3 (initial checkpoint)
    createEdits(20); //23
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    h.doCheckpoint(); //25
    createEdits(20); //45
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(45, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
    InjectionHandler.clear();
  }

  @Test
  public void testAcrossRestarts() throws Exception {
    setUp("testAcrossRestarts");
    // 3 initial checkpoint
    createEdits(20); //23
    cluster.restartAvatarNodes(); //25 + 2 restart finalizes the segment + checkpoint
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    
    assertEquals(27, getCurrentTxId(primary));
    // give time to the standby to start-up
    Thread.sleep(2000);
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
    createEdits(20);
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(47, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
  }

  @Test
  public void testCheckpointAndRestart() throws Exception {
    TestAvatarTxIdsHandler h = new TestAvatarTxIdsHandler();
    InjectionHandler.set(h);
    setUp("testCheckpointAndRestart");
    // 3 initial checkpoint
    createEdits(20); //23
    AvatarNode primary = cluster.getPrimaryAvatar(0).avatar;
    AvatarNode standby = cluster.getStandbyAvatar(0).avatar;
    h.doCheckpoint(); //25 checkpoint adds 2 transactions
    createEdits(20);
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(45, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));

    cluster.restartAvatarNodes(); //49 (restart + initial checkpoint)
    primary = cluster.getPrimaryAvatar(0).avatar;
    standby = cluster.getStandbyAvatar(0).avatar;
    createEdits(20);
    standby.quiesceStandby(getCurrentTxId(primary)-1);
    assertEquals(69, getCurrentTxId(primary));
    assertEquals(getCurrentTxId(primary), getCurrentTxId(standby));
    InjectionHandler.clear();
  }
  
  class TestAvatarTxIdsHandler extends InjectionHandler {
    private CheckpointTrigger ckptTrigger = new CheckpointTrigger();
       
    @Override
    protected void _processEvent(InjectionEvent event, Object... args) {
      ckptTrigger.checkpointDone(event, args);
    }
    
    @Override
    protected boolean _falseCondition(InjectionEvent event, Object... args) {
      return ckptTrigger.triggerCheckpoint(event);
    }
    
    void doCheckpoint() throws IOException {
      ckptTrigger.doCheckpoint();
    }
  }
}
