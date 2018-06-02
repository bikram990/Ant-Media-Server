package io.antmedia.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;
import javax.xml.soap.Text;

import org.bytedeco.javacpp.avformat;
import org.bytedeco.javacpp.avutil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;
import org.mongodb.morphia.Datastore;
import org.red5.server.scheduling.QuartzSchedulingService;
import org.red5.server.scope.WebScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.AppSettings;
import io.antmedia.EncoderSettings;
import io.antmedia.datastore.db.IDataStore;
import io.antmedia.datastore.db.InMemoryDataStore;
import io.antmedia.datastore.db.MapDBStore;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.integration.MuxingTest;
import io.antmedia.integration.RestServiceTest;
import io.antmedia.muxer.MuxAdaptor;
import io.antmedia.rest.model.Result;
import io.antmedia.streamsource.StreamFetcher;
import io.antmedia.streamsource.StreamFetcherManager;
import io.antmedia.streamsource.StreamFetcherManager.StreamFetcherFactory;

@ContextConfiguration(locations = { "test.xml" })
public class StreamFetcherUnitTest extends AbstractJUnit4SpringContextTests {

	@Context
	private ServletContext servletContext;
	private WebScope appScope;
	protected static Logger logger = LoggerFactory.getLogger(StreamFetcherUnitTest.class);
	public AntMediaApplicationAdapter app = null;
	private AntMediaApplicationAdapter appInstance;
	private AppSettings appSettings;
	private QuartzSchedulingService scheduler;


	static {
		System.setProperty("red5.deployment.type", "junit");
		System.setProperty("red5.root", ".");
	}

	@BeforeClass
	public static void beforeClass() {
		avformat.av_register_all();
		avformat.avformat_network_init();
		avutil.av_log_set_level(avutil.AV_LOG_INFO);

	}

	@Before
	public void before() {

		File webApps = new File("webapps");
		if (!webApps.exists()) {
			webApps.mkdirs();
		}
		File junit = new File(webApps, "junit");
		if (!junit.exists()) {
			junit.mkdirs();
		}


		if (appScope == null) {
			appScope = (WebScope) applicationContext.getBean("web.scope");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}

		if (app == null) 
		{

			app = (AntMediaApplicationAdapter) applicationContext.getBean("web.handler");
			logger.debug("Application / web scope: {}", appScope);
			assertTrue(appScope.getDepth() == 1);
		}
		
		scheduler = (QuartzSchedulingService) applicationContext.getBean(QuartzSchedulingService.BEAN_NAME);


		stopCameraEmulator();

		AppSettings defaultSettings = new AppSettings();

		//reset values in the bean
		getAppSettings().setMp4MuxingEnabled(defaultSettings.isMp4MuxingEnabled());
		getAppSettings().setHlsMuxingEnabled(defaultSettings.isHlsMuxingEnabled());

		getAppSettings().setMp4MuxingEnabled(true);
		getAppSettings().setAddDateTimeToMp4FileName(defaultSettings.isAddDateTimeToMp4FileName());
		getAppSettings().setHlsMuxingEnabled(defaultSettings.isHlsMuxingEnabled());
		getAppSettings().setWebRTCEnabled(defaultSettings.isWebRTCEnabled());
		getAppSettings().setDeleteHLSFilesOnEnded(defaultSettings.isDeleteHLSFilesOnExit());
		getAppSettings().setHlsListSize(defaultSettings.getHlsListSize());
		getAppSettings().setHlsTime(defaultSettings.getHlsTime());
		getAppSettings().setHlsPlayListType(defaultSettings.getHlsPlayListType());
		getAppSettings().setAdaptiveResolutionList(defaultSettings.getAdaptiveResolutionList());

	}

	@After
	public void after() {

		stopCameraEmulator();

		appScope = null;
		app = null;

		try {
			delete(new File("webapps"));
		} catch (IOException e) {
			e.printStackTrace();
		}

	}


	@Test
	public void testBugUpdateStreamFetcherStatus() {

		logger.info("starting testBugUpdateStreamFetcherStatus");
		
		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		//create ip camera broadcast
		IDataStore dataStore = new MapDBStore("target/testbug.db"); //applicationContext.getBean(IDataStore.BEAN_NAME);

		assertNotNull(dataStore);
		app.setDataStore(dataStore);

		//set mapdb datastore to stream fetcher because in memory datastore just have references and updating broadcst
		// object updates the reference in inmemorydatastore
		app.getStreamFetcherManager().setDatastore(dataStore);

		app.getStreamFetcherManager().setRestartStreamAutomatically(false);
		app.getStreamFetcherManager().setStreamCheckerInterval(5000);

		app.getStreamFetcherManager().getStreamFetcherList().clear();

		assertEquals(0, app.getStreamFetcherManager().getStreamFetcherList().size());

		//save it data store
		Broadcast newCam = new Broadcast("testOnvif", "127.0.0.1:8080", "admin", "admin", "rtsp://127.0.0.1:6554/test.flv",
				AntMediaApplicationAdapter.IP_CAMERA);
		String id = dataStore.save(newCam);


		//set status to broadcasting
		dataStore.updateStatus(id, AntMediaApplicationAdapter.BROADCAST_STATUS_BROADCASTING);
		Broadcast broadcast = dataStore.get(id);
		logger.info("broadcast stream id {}" , id);
		assertEquals(broadcast.getStatus(), AntMediaApplicationAdapter.BROADCAST_STATUS_BROADCASTING);

		//start StreamFetcher
		app.getStreamFetcherManager().startStreams(Arrays.asList(broadcast));
		
		assertEquals(1, scheduler.getScheduledJobNames().size());

		assertEquals(1, app.getStreamFetcherManager().getStreamFetcherList().size());

		//wait 5seconds because connectivity time out is 4sec by default
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}


		//check that it is not started
		boolean flag3 = false;
		for (StreamFetcher camScheduler : app.getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(newCam.getIpAddr())) {
				// it should be false because emulator has not been started yet
				assertFalse(camScheduler.isStreamAlive());
				flag3 = true;

			}
		}

		assertTrue(flag3);

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		//check that broadcast status in datastore in finished or not broadcasting
		broadcast = dataStore.get(id);
		assertEquals(AntMediaApplicationAdapter.BROADCAST_STATUS_BROADCASTING, broadcast.getStatus());
		assertEquals(MuxAdaptor.QUALITY_POOR, broadcast.getQuality());
		assertEquals(0, broadcast.getSpeed(), 2L);


		app.getStreamFetcherManager().stopStreaming(newCam);
		assertEquals(0, app.getStreamFetcherManager().getStreamFetcherList().size());


		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		app.stopStreaming(newCam);
		
		assertEquals(1, scheduler.getScheduledJobNames().size());
		

		logger.info("leaving testBugUpdateStreamFetcherStatus");

	}
	

	@Test
	public void testRestartPeriodStreamFetcher() {

		boolean deleteHLSFilesOnExit = getAppSettings().isDeleteHLSFilesOnExit();
		getAppSettings().setDeleteHLSFilesOnEnded(false);
		try {
			//Create Stream Fetcher Manager
			assertEquals(1, scheduler.getScheduledJobNames().size());
			
			InMemoryDataStore memoryDataStore = new InMemoryDataStore("testdb");

			//Create a mock StreamFetcher and add it to StreamFetcherManager
			StreamFetcher streamFetcher = Mockito.mock(StreamFetcher.class);
			Broadcast stream =  Mockito.mock(Broadcast.class);

			stream.setStreamId(String.valueOf((Math.random() * 100000)));

			stream.setStreamUrl("anyurl");
			streamFetcher.setStream(stream);
			when(streamFetcher.getStream()).thenReturn(stream);
			when(streamFetcher.isStreamAlive()).thenReturn(true);
			when(streamFetcher.getCameraError()).thenReturn(new Result(true));

			StreamFetcherFactory factory = mock(StreamFetcherFactory.class);
			when(factory.make(stream, appScope, scheduler)).thenReturn(streamFetcher);

			StreamFetcherManager fetcherManager = new StreamFetcherManager(scheduler, memoryDataStore, appScope, factory);

			//set checker interval to 3 seconds
			fetcherManager.setStreamCheckerInterval(4000);

			//set restart period to 5 seconds
			fetcherManager.setRestartStreamFetcherPeriod(5);

			//Start stream fetcher
			Result result = fetcherManager.startStreaming(stream);
			assertTrue(result.isSuccess());
			
			
			//wait 10-12 seconds
			Thread.sleep(13000);

			//check that stream fetcher stop and start stream is called 4 times
			verify(streamFetcher, times(2)).stopStream();

			//it is +1 because it is called at first start
			verify(streamFetcher, times(3)).startStream(); 
			verify(streamFetcher, times(3)).startStream(); 

			//set restart period to 0 seconds
			fetcherManager.setRestartStreamFetcherPeriod(0);

			//wait 10-12 seconds
			Thread.sleep(13000);

			//check that stream fetcher stop and start stream is not called
			verify(streamFetcher, times(2)).stopStream();
			verify(streamFetcher, times(3)).startStream(); 

			//set restart period to 0 seconds
			fetcherManager.setRestartStreamFetcherPeriod(5);

			//wait 10-12 seconds
			Thread.sleep(13000);

			//check that stream fetcher stop and start stream is not called
			verify(streamFetcher, atLeast(4)).stopStream();
			verify(streamFetcher, atLeast(5)).startStream(); 

			fetcherManager.setRestartStreamFetcherPeriod(0);
			
			fetcherManager.stopCheckerJob();

		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		
		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		getAppSettings().setDeleteHLSFilesOnEnded(deleteHLSFilesOnExit);
		

	}

	@Test
	public void testThreadStopStart() {

		logger.info("starting testThreadStopStart");

		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		try {

			// start stream fetcher

			Broadcast newCam = new Broadcast("onvifCam1", "127.0.0.1:8080", "admin", "admin", "rtsp://127.0.0.1:6554/test.flv",
					AntMediaApplicationAdapter.IP_CAMERA);
			assertNotNull(newCam.getStreamUrl());

			try {
				newCam.setStreamId((int)Math.random()*100000 + "");
			} catch (Exception e) {
				e.printStackTrace();
				fail(e.getMessage());
			}

			assertNotNull(newCam.getStreamId());

			StreamFetcher fetcher = new StreamFetcher(newCam, appScope, scheduler);


			startCameraEmulator();

			// thread start 
			fetcher.startStream();

			Thread.sleep(6000);

			//check that thread is running
			assertTrue(fetcher.isThreadActive());
			assertTrue(fetcher.isStreamAlive());


			//stop thread
			fetcher.stopStream();

			Thread.sleep(5000);

			assertFalse(fetcher.isStreamAlive());
			assertFalse(fetcher.isThreadActive());

			//change the flag that shows thread is still running
			fetcher.setThreadActive(true);

			//start thread
			fetcher.startStream();

			Thread.sleep(6000);
			//check that thread is not started because thread active is true
			assertFalse(fetcher.isStreamAlive());
			assertTrue(fetcher.isThreadActive());


			logger.info("Change the flag that previous thread is stopped");
			//change the flag that previous thread is stopped
			fetcher.setThreadActive(false);

			//wait a little
			Thread.sleep(5000);

			//check that thread is started
			assertTrue(fetcher.isStreamAlive());
			assertTrue(fetcher.isThreadActive());

			fetcher.stopStream();

			Thread.sleep(6000);
			assertFalse(fetcher.isStreamAlive());
			assertFalse(fetcher.isThreadActive());

			stopCameraEmulator();

			Thread.sleep(3000);

		} catch (Exception e) {
			e.printStackTrace();
		}

		logger.info("leaving testThreadStopStart");
		assertEquals(1, scheduler.getScheduledJobNames().size());
		
	}

	@Test
	public void testCameraErrorCodes() {

		logger.info("starting testCameraErrorCodes");

		try {
			assertEquals(1, scheduler.getScheduledJobNames().size());
			
			// start stream fetcher

			Broadcast newCam = new Broadcast("onvifCam2", "127.0.0.1:8080", "admin", "admin", "rtsp://10.122.59.79:6554/test.flv",
					AntMediaApplicationAdapter.IP_CAMERA);
			assertNotNull(newCam.getStreamUrl());

			try {
				newCam.setStreamId((int)Math.random()*100000 + "");
			} catch (Exception e) {
				e.printStackTrace();
				fail(e.getMessage());
			}

			assertNotNull(newCam.getStreamId());

			StreamFetcher fetcher = new StreamFetcher(newCam, appScope, scheduler);

			// thread start 
			fetcher.startStream();

			Thread.sleep(8000);

			String str=fetcher.getCameraError().getMessage();
			logger.info("error:   "+str);

			assertNotNull(fetcher.getCameraError().getMessage());

			assertTrue(fetcher.getCameraError().getMessage().contains("timed out"));

			fetcher.stopStream();

			Thread.sleep(2000);

			// start stream fetcher

			Broadcast newCam2 = new Broadcast("onvifCam3", "127.0.0.1:8080", "admin", "admin", "rtsp://127.0.0.1:6554/test.flv",
					AntMediaApplicationAdapter.IP_CAMERA);
			assertNotNull(newCam2.getStreamUrl());

			try {
				newCam2.setStreamId("543534534534534");
			} catch (Exception e) {
				e.printStackTrace();
				fail(e.getMessage());
			}

			assertNotNull(newCam2.getStreamId());

			StreamFetcher fetcher2 = new StreamFetcher(newCam2, appScope, scheduler);

			// thread start 
			fetcher2.startStream();

			Thread.sleep(6000);

			String str2=fetcher2.getCameraError().getMessage();
			logger.info("error2:   "+str2);



			assertTrue(fetcher2.getCameraError().getMessage().contains("Connection refused"));

			fetcher2.stopStream();

			Thread.sleep(2000);

		} catch (Exception e) {
			e.printStackTrace();
		}
		assertEquals(1, scheduler.getScheduledJobNames().size());
		
	}
	
	

	@Test
	public void testStreamFetcherBuffer() {

		
		try {	
			assertEquals(1, scheduler.getScheduledJobNames().size());
			getAppSettings().setDeleteHLSFilesOnEnded(false);
			
			Broadcast newCam = new Broadcast("streamSource", "127.0.0.1:8080", "admin", "admin", 
					"src/test/resources/test_video_360p.flv",
					AntMediaApplicationAdapter.STREAM_SOURCE);

			assertNotNull(newCam.getStreamUrl());

			String id = getInstance().getDataStore().save(newCam);

			assertNotNull(newCam.getStreamId());

			StreamFetcher fetcher = new StreamFetcher(newCam, appScope, scheduler);
			
			fetcher.setBufferTime(20000);

			fetcher.setRestartStream(false);

			assertFalse(fetcher.isThreadActive());
			assertFalse(fetcher.isStreamAlive());

			// start 
			fetcher.startStream();

			//wait for fetching stream
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			//wait for packaging files
			fetcher.stopStream();

			try {
				Thread.sleep(8000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			assertFalse(fetcher.isThreadActive());

			logger.info("before test m3u8 file");

			assertTrue(MuxingTest.testFile("webapps/junit/streams/"+newCam.getStreamId() +".m3u8"));

			logger.info("after test m3u8 file");
			//tmp file should be deleted
			File f = new File("webapps/junit/streams/"+newCam.getStreamId() +".mp4.tmp_extension");
			assertFalse(f.exists());


			f = new File("webapps/junit/streams/"+newCam.getStreamId() +".mp4");
			assertTrue(f.exists());


			logger.info("before test mp4 file");

			assertTrue(MuxingTest.testFile("webapps/junit/streams/"+newCam.getStreamId() +".mp4", 146000));

			logger.info("after test mp4 file");

			getInstance().getDataStore().delete(id);
			
			
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		
		List<String> scheduledJobNames = scheduler.getScheduledJobNames();
		for (String jobName : scheduledJobNames) {
			logger.info("Schedule name: {}", jobName);
		}
	
		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		getAppSettings().setDeleteHLSFilesOnEnded(true);

	}

	@Test
	public void testCameraStartedProperly() {
		try {

			assertEquals(1, scheduler.getScheduledJobNames().size());
			
			startCameraEmulator();

			Thread.sleep(2000);

			// start stream fetcher

			Broadcast newCam3 = new Broadcast("onvifCam4", "127.0.0.1:8080", "admin", "admin", "rtsp://127.0.0.1:6554/test.flv",
					AntMediaApplicationAdapter.IP_CAMERA);
			assertNotNull(newCam3.getStreamUrl());


			newCam3.setStreamId("stream_id_" + (int)(Math.random() * 100000));


			Thread.sleep(3000);


			StreamFetcher fetcher3 = new StreamFetcher(newCam3, appScope, scheduler);

			// thread start 
			fetcher3.startStream();

			Thread.sleep(6000);

			String str3=fetcher3.getCameraError().getMessage();
			logger.info("error:   "+str3);

			assertNull(fetcher3.getCameraError().getMessage());
			assertTrue(fetcher3.isStreamAlive());



			fetcher3.stopStream();

			Thread.sleep(2000);
			stopCameraEmulator();
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		assertEquals(1, scheduler.getScheduledJobNames().size());
	}

	/**
	 * this test is NA anymore, because StartStop mechanism is managed by itself not by StremFetcherManager
	 */
	//@Test
	public void testCameraCheckerStartStop() {

		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		logger.info("starting testCameraCheckerStartStop");

		// define camera according to onvif emulator parameters
		Broadcast newCam = new Broadcast("testOnvif", "127.0.0.1:8080", "admin", "admin", "rtsp://127.0.0.1:6554/test.flv",
				AntMediaApplicationAdapter.IP_CAMERA);

		try {
			newCam.setStreamId("stream_" + (int)(Math.random() * 10000));
		} catch (Exception e2) {
			e2.printStackTrace();
			fail(e2.getMessage());
		}

		List<Broadcast> cameras = new ArrayList<>();

		cameras.add(newCam);

		assertNotNull(app.getDataStore());

		app.getStreamFetcherManager().getStreamFetcherList().clear();

		assertEquals(0, app.getStreamFetcherManager().getStreamFetcherList().size());

		//sets stream fetcher configuration, it checks streams in every 15sec
		app.getStreamFetcherManager().setStreamCheckerInterval(15000);
		logger.info("starting new streams in testCameraCheckerStartStop");
		app.getStreamFetcherManager().startStreams(cameras);
		logger.info("started new streams in testCameraCheckerStartStop");


		try {
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		boolean flag3 = false;
		for (StreamFetcher camScheduler : app.getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(newCam.getIpAddr())) {
				// it should be false because emulator has not been started yet
				assertFalse(camScheduler.isStreamAlive());
				assertFalse(camScheduler.isThreadActive());
				flag3 = true;

			}
		}

		assertTrue(flag3);
		startCameraEmulator();

		logger.warn("emulater has been started");

		try {

			//wait more than 15sec to make sure scheduler start the stream again
			Thread.sleep(18000);

		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		boolean flag = false;
		for (StreamFetcher camScheduler : app.getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(newCam.getIpAddr())) {
				// it should be true because emulator has been started
				assertTrue(camScheduler.isStreamAlive());
				flag = true;
			}
		}

		assertTrue(flag);

		stopCameraEmulator();

		try {
			//waiting 5 sec is ok. Because stream is not alive if last packet time is older than 3 secs.
			Thread.sleep(5000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		boolean flag2 = false;
		for (StreamFetcher camScheduler : app.getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(newCam.getIpAddr())) {
				// it should be false because connection is down between
				// emulator and server
				assertFalse(camScheduler.isStreamAlive());
				flag2 = true;
			}

		}
		assertTrue(flag2);
		startCameraEmulator();

		try {
			//wait more than 15sec to make sure stream is started again
			Thread.sleep(18000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		boolean flag5 = false;
		for (StreamFetcher camScheduler : app.getStreamFetcherManager().getStreamFetcherList()) {
			if (camScheduler.getStream().getIpAddr().equals(newCam.getIpAddr())) {
				// after 30 seconds, adaptor should check and start because
				// thread was not working
				assertTrue(camScheduler.isStreamAlive());
				flag5 = true;
			}

		}
		assertTrue(flag5);
		stopCameraEmulator();
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		app.getStreamFetcherManager().stopStreaming(newCam);
		assertEquals(0, app.getStreamFetcherManager().getStreamFetcherList().size());


		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		logger.info("leaving testCameraCheckerStartStop");

	}

	/*
	@Test
	public void testStreamFetcherSources() {
		logger.info("running testStreamFetcherSources src/test/resources/test_video_360p.flv");
		//test FLV Source
		testFetchStreamSources("src/test/resources/test_video_360p.flv");

		logger.info("running testStreamFetcherSources rtmp://184.72.239.149/vod/mp4:bigbuckbunny_1500.mp4");
		//test RTMP Source
		testFetchStreamSources("rtmp://184.72.239.149/vod/mp4:bigbuckbunny_1500.mp4");

		logger.info("running testStreamFetcherSources rtsp://127.0.0.1:6554/test.flv");
		startCameraEmulator();
		//test RTSP Source
		testFetchStreamSources("rtsp://127.0.0.1:6554/test.flv");
		stopCameraEmulator();

	}

	 */
	@Test
	public void testFLVSource() {
		logger.info("running testFLVSource");
		//test FLV Source
		testFetchStreamSources("src/test/resources/test_video_360p.flv", false);	
		logger.info("leaving testFLVSource");
	}


	@Test
	public void testRTSPSource() {
		startCameraEmulator();
		logger.info("running testRTSPSource");
		//test RTSP Source
		testFetchStreamSources("rtsp://127.0.0.1:6554/test.flv", true);	
		logger.info("leaving testRTSPSource");
		stopCameraEmulator();
	}



	@Test
	public void testHLSSource() {
		logger.info("running testHLSSource");
		
		//test HLS Source
		testFetchStreamSources("src/test/resources/test.m3u8", false);	
		logger.info("leaving testHLSSource");
	}

	@Test
	public void testTSSource() {
		logger.info("running testTSSource");
		//test TS Source
		testFetchStreamSources("src/test/resources/test.ts", false);
		logger.info("leaving testTSSource");
	}


	public void testFetchStreamSources(String source, boolean restartStream) {

		boolean deleteHLSFilesOnExit = getAppSettings().isDeleteHLSFilesOnExit();
		try {
			getAppSettings().setDeleteHLSFilesOnEnded(false);

			assertEquals(1, scheduler.getScheduledJobNames().size());
			
			Broadcast newCam = new Broadcast("streamSource", "127.0.0.1:8080", "admin", "admin", source,
					AntMediaApplicationAdapter.STREAM_SOURCE);

			assertNotNull(newCam.getStreamUrl());

			String id = getInstance().getDataStore().save(newCam);

			assertNotNull(newCam.getStreamId());

			StreamFetcher fetcher = new StreamFetcher(newCam, appScope, scheduler);

			fetcher.setRestartStream(restartStream);

			assertFalse(fetcher.isThreadActive());
			assertFalse(fetcher.isStreamAlive());

			// start 
			fetcher.startStream();

			//wait for fetching stream
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			//wait for packaging files
			fetcher.stopStream();

			try {
				Thread.sleep(8000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			assertFalse(fetcher.isThreadActive());

			logger.info("before test m3u8 file");

			assertTrue(MuxingTest.testFile("webapps/junit/streams/"+newCam.getStreamId() +".m3u8"));

			logger.info("after test m3u8 file");
			//tmp file should be deleted
			File f = new File("webapps/junit/streams/"+newCam.getStreamId() +".mp4.tmp_extension");
			assertFalse(f.exists());


			f = new File("webapps/junit/streams/"+newCam.getStreamId() +".mp4");
			assertTrue(f.exists());


			logger.info("before test mp4 file");

			assertTrue(MuxingTest.testFile("webapps/junit/streams/"+newCam.getStreamId() +".mp4"));

			logger.info("after test mp4 file");

			getInstance().getDataStore().delete(id);
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		
		assertEquals(1, scheduler.getScheduledJobNames().size());
		getAppSettings().setDeleteHLSFilesOnEnded(deleteHLSFilesOnExit);


	}

	@Test
	public void testHLSFlagResult() {

		boolean deleteHLSFilesOnExit = getAppSettings().isDeleteHLSFilesOnExit();
		getAppSettings().setDeleteHLSFilesOnEnded(false);
		
		try {
			String textInFile;

			assertEquals(1, scheduler.getScheduledJobNames().size());
			
			Broadcast newCam = new Broadcast("streamSource", "127.0.0.1:8080", "admin", "admin", "src/test/resources/test.ts",
					AntMediaApplicationAdapter.STREAM_SOURCE);

			assertNotNull(newCam.getStreamUrl());

			String id = getInstance().getDataStore().save(newCam);

			assertNotNull(newCam.getStreamId());

			StreamFetcher fetcher = new StreamFetcher(newCam, appScope, scheduler);


			fetcher.setRestartStream(false);

			assertFalse(fetcher.isThreadActive());
			assertFalse(fetcher.isStreamAlive());

			// start 
			fetcher.startStream();

			//wait for fetching stream
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			//wait for packaging files
			fetcher.stopStream();

			try {
				Thread.sleep(3000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			assertFalse(fetcher.isThreadActive());

			assertTrue(MuxingTest.testFile("webapps/junit/streams/"+newCam.getStreamId() +".m3u8"));

			BufferedReader br = new BufferedReader(new FileReader("webapps/junit/streams/"+newCam.getStreamId() +".m3u8"));
			try {
				StringBuilder sb = new StringBuilder();
				String line = br.readLine();

				while (line != null) {
					sb.append(line);
					//  sb.append(System.lineSeparator());
					line = br.readLine();
				}
				textInFile = sb.toString();

				logger.info(textInFile);
			} finally {
				br.close();
			}

			//Check that m3u8 file does not include "EXT-X-ENDLIST" parameter because "omit_endlist" flag is used in HLS Muxer

			assertFalse(textInFile.contains("EXT-X-ENDLIST"));

			getInstance().getDataStore().delete(id);
		}
		catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
		}
		assertEquals(1, scheduler.getScheduledJobNames().size());
		
		getAppSettings().setDeleteHLSFilesOnEnded(deleteHLSFilesOnExit);
		
		
	}


	private void startCameraEmulator() {
		stopCameraEmulator();

		ProcessBuilder pb = new ProcessBuilder("/usr/local/onvif/runme.sh");
		Process p = null;
		try {
			p = pb.start();
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}


	private void stopCameraEmulator() {
		// close emulator in order to simulate cut-off
		String[] argsStop = new String[] { "/bin/bash", "-c",
		"kill -9 $(ps aux | grep 'onvifser' | awk '{print $2}')" };
		String[] argsStop2 = new String[] { "/bin/bash", "-c",
		"kill -9 $(ps aux | grep 'rtspserve' | awk '{print $2}')" };
		try {
			Process procStop = new ProcessBuilder(argsStop).start();
			Process procStop2 = new ProcessBuilder(argsStop2).start();


		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}


	public static void delete(File file)
			throws IOException{

		if(file.isDirectory()){

			//directory is empty, then delete it
			if(file.list().length==0){

				file.delete();
				//System.out.println("Directory is deleted : " 
				//	+ file.getAbsolutePath());

			}else{

				//list all the directory contents
				String files[] = file.list();

				for (String temp : files) {
					//construct the file structure
					File fileDelete = new File(file, temp);

					//recursive delete
					delete(fileDelete);
				}

				//check the directory again, if empty then delete it
				if(file.list().length==0){
					file.delete();
					//System.out.println("Directory is deleted : " 
					//		+ file.getAbsolutePath());
				}
			}

		}else{
			//if file, then delete it
			file.delete();
			//System.out.println("File is deleted : " + file.getAbsolutePath());
		}
	}



	public AntMediaApplicationAdapter getInstance() {
		if (appInstance == null) {
			appInstance = (AntMediaApplicationAdapter) applicationContext.getBean("web.handler");
		}
		return appInstance;
	}

	public AppSettings getAppSettings() {
		if (appSettings == null) {
			appSettings = (AppSettings) applicationContext.getBean(AppSettings.BEAN_NAME);
		}
		return appSettings;
	}

}
