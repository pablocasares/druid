/*
 * Druid - a distributed column store.
 * Copyright (C) 2012  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.metamx.druid.realtime.plumber;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.metamx.common.ISE;
import com.metamx.druid.Query;
import com.metamx.druid.QueryGranularity;
import com.metamx.druid.aggregation.AggregatorFactory;
import com.metamx.druid.aggregation.CountAggregatorFactory;
import com.metamx.druid.client.DataSegment;
import com.metamx.druid.client.ServerView;
import com.metamx.druid.coordination.DataSegmentAnnouncer;
import com.metamx.druid.index.v1.IndexGranularity;
import com.metamx.druid.index.v1.SpatialDimensionSchema;
import com.metamx.druid.loading.DataSegmentPusher;
import com.metamx.druid.query.DefaultQueryRunnerFactoryConglomerate;
import com.metamx.druid.query.QueryRunnerFactory;
import com.metamx.druid.realtime.FireDepartmentMetrics;
import com.metamx.druid.realtime.Schema;
import com.metamx.druid.realtime.SegmentPublisher;
import com.metamx.druid.shard.NoneShardSpec;
import com.metamx.emitter.service.ServiceEmitter;
import junit.framework.Assert;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.easymock.EasyMock;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 */
public class RealtimePlumberSchoolTest
{
  private Plumber plumber;

  private DataSegmentAnnouncer announcer;
  private SegmentPublisher segmentPublisher;
  private DataSegmentPusher dataSegmentPusher;
  private ServerView serverView;
  private ServiceEmitter emitter;

  @Before
  public void setUp() throws Exception
  {

    final File tmpDir = Files.createTempDir();
    tmpDir.deleteOnExit();

    final Schema schema = new Schema(
        "test",
        Lists.<SpatialDimensionSchema>newArrayList(),
        new AggregatorFactory[]{new CountAggregatorFactory("rows")},
        QueryGranularity.NONE,
        new NoneShardSpec()
    );

    RealtimePlumberSchool realtimePlumberSchool = new RealtimePlumberSchool(
        new Period("PT10m"),
        tmpDir,
        IndexGranularity.HOUR
    );

    announcer = EasyMock.createMock(DataSegmentAnnouncer.class);
    announcer.announceSegment(EasyMock.<DataSegment>anyObject());
    EasyMock.expectLastCall().anyTimes();

    segmentPublisher = EasyMock.createMock(SegmentPublisher.class);
    dataSegmentPusher = EasyMock.createMock(DataSegmentPusher.class);

    serverView = EasyMock.createMock(ServerView.class);
    serverView.registerSegmentCallback(
        EasyMock.<Executor>anyObject(),
        EasyMock.<ServerView.SegmentCallback>anyObject()
    );
    EasyMock.expectLastCall().anyTimes();

    emitter = EasyMock.createMock(ServiceEmitter.class);

    EasyMock.replay(announcer, segmentPublisher, dataSegmentPusher, serverView, emitter);

    realtimePlumberSchool.setConglomerate(new DefaultQueryRunnerFactoryConglomerate(Maps.<Class<? extends Query>, QueryRunnerFactory>newHashMap()));
    realtimePlumberSchool.setSegmentAnnouncer(announcer);
    realtimePlumberSchool.setSegmentPublisher(segmentPublisher);
    realtimePlumberSchool.setRejectionPolicyFactory(new NoopRejectionPolicyFactory());
    realtimePlumberSchool.setVersioningPolicy(new IntervalStartVersioningPolicy());
    realtimePlumberSchool.setDataSegmentPusher(dataSegmentPusher);
    realtimePlumberSchool.setServerView(serverView);
    realtimePlumberSchool.setServiceEmitter(emitter);

    plumber = realtimePlumberSchool.findPlumber(schema, new FireDepartmentMetrics());
  }

  @After
  public void tearDown() throws Exception
  {
    EasyMock.verify(announcer, segmentPublisher, dataSegmentPusher, serverView, emitter);
  }

  @Test
  public void testGetSink() throws Exception
  {
    final DateTime theTime = new DateTime("2013-01-01");
    Sink sink = plumber.getSink(theTime.getMillis());

    Assert.assertEquals(new Interval(String.format("%s/PT1H", theTime.toString())), sink.getInterval());
    Assert.assertEquals(theTime.toString(), sink.getVersion());
  }

  @Test
  public void testPersist() throws Exception
  {
    final MutableBoolean committed = new MutableBoolean(false);
    plumber.startJob();
    plumber.persist(
        new Runnable()
        {
          @Override
          public void run()
          {
            committed.setValue(true);
          }
        }
    );

    Stopwatch stopwatch = new Stopwatch().start();
    while (!committed.booleanValue()) {
      Thread.sleep(100);
      if (stopwatch.elapsed(TimeUnit.MILLISECONDS) > 1000) {
        throw new ISE("Taking too long to set perist value");
      }
    }
    plumber.finishJob();
  }
}
