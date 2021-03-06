/**
 * Copyright 2016 Ambud Sharma
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.srotya.tau.nucleus.wal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteOptions;
import org.rocksdb.util.SizeUnit;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.srotya.tau.nucleus.Utils;
import com.srotya.tau.nucleus.processor.AbstractProcessor;
import com.srotya.tau.wraith.Event;
import com.srotya.tau.wraith.EventFactory;

/**
 * {@link WAL} implementation based on Facebook's {@link RocksDB} which is
 * sorted key value store inspired by HBase. <br>
 * <br>
 * It offers high performance features with persistence making it really good
 * for a {@link WAL} use case as it's optimized for flash storage thus keeping
 * the primary tree in memory (RAMFS) and {@link RocksDB}'s Write-Ahead-Log on
 * disk. <br>
 * <br>
 * Therefore faults can be reliably tolerated while achieving fairly high
 * performance. This is better than Kafka for the Nucleus inter-processor queue
 * use case since individual events can be acknowledged without loosing track of
 * the earliest unacknowledged event.
 * 
 * @author ambudsharma
 */
public class RocksDBWALService implements WAL {

	private static final Logger logger = Logger.getLogger(RocksDBWALService.class.getName());
	private RocksDB wal;
	private Options options;
	private String walDirectory;
	private boolean autoResetWal = false;
	private String mapDirectory;
	private AbstractProcessor processor;
	private EventFactory factory;
	private WriteOptions writeOptions;
	private static final ThreadLocal<Kryo> kryoPool = new ThreadLocal<Kryo>() {
		public Kryo get() {
			return new Kryo();
		}
	};

	static {
		RocksDB.loadLibrary();
	}

	public RocksDBWALService() {
	}

	@SuppressWarnings("resource")
	@Override
	public void start() throws Exception {
		if (autoResetWal) {
			Utils.wipeDirectory(walDirectory);
			Utils.wipeDirectory(mapDirectory);
			logger.info("Cleared WAL directory:" + walDirectory);
		}
		options = new Options().setCreateIfMissing(true).setAllowMmapReads(true).setAllowMmapWrites(true)
				.setIncreaseParallelism(2).setFilterDeletes(true).setMaxBackgroundCompactions(10)
				.setMaxBackgroundFlushes(10).setDisableDataSync(false).setUseFsync(false).setUseAdaptiveMutex(false)
				.setWriteBufferSize(1 * SizeUnit.MB).setCompactionStyle(CompactionStyle.UNIVERSAL)
				.setMaxWriteBufferNumber(6).setWalTtlSeconds(60).setWalSizeLimitMB(512)
				.setMaxTotalWalSize(1024 * SizeUnit.MB).setErrorIfExists(false).setAllowOsBuffer(true)
				.setWalDir(walDirectory).setOptimizeFiltersForHits(false);
		if (!System.getProperties().get("os.name").toString().toLowerCase().contains("windows")) {
			options = options.setCompressionType(CompressionType.SNAPPY_COMPRESSION);
		}
		wal = RocksDB.open(options, mapDirectory);
		writeOptions = new WriteOptions().setDisableWAL(false).setSync(false);
		recoverAndReplayEvents();
	}

	public void recoverAndReplayEvents() {
		RocksIterator itr = wal.newIterator();
		try {
			itr.seekToFirst();
			while (itr.isValid() && itr.key() != null) {
				Long id = Utils.byteToLong(itr.key());
				Kryo kryo = kryoPool.get();
				Input input = new Input(itr.value());
				@SuppressWarnings("unchecked")
				Map<String, Object> headers = kryo.readObject(input, HashMap.class);
				logger.fine("Recovered non-acked event, eventid:" + id + "\theaders:" + headers);
				Event event = factory.buildEvent();
				event.setEventId(id);
				event.getHeaders().putAll(headers);
				event.setBody(itr.value());
				processor.processEventNonWaled(event);
				itr.next();
			}
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Unable to recover events", e);
		} finally {
			itr.close();
		}
	}

	@Override
	public void stop() throws Exception {
		wal.close();
		writeOptions.close();
		options.close();
	}

	public void writeEvent(Event event) throws IOException {
		try {
			Kryo kryo = kryoPool.get();
			ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
			Output output = new Output(bos);
			kryo.writeObject(output, event.getHeaders());
			output.close();
			wal.put(Utils.longToBytes(event.getEventId()), bos.toByteArray());
			bos.close();
		} catch (RocksDBException e) {
			throw new IOException(e);
		}
	}

	/**
	 * @return the parserWal
	 */
	protected RocksDB getParserWal() {
		return wal;
	}

	@Override
	public void ackEvent(Long eventId) throws IOException {
		try {
			wal.remove(writeOptions, Utils.longToBytes(eventId));
		} catch (RocksDBException e) {
			throw new IOException(e);
		}
	}

	@Override
	public Long getEarliestEventId() throws IOException {
		RocksIterator itr = wal.newIterator();
		itr.seekToFirst();
		if (!itr.isValid()) {
			return null;
		}
		long val = Utils.byteToLong(itr.key());
		itr.close();
		return val;
	}

	@Override
	public void setPersistentDirectory(String persistentDirectory) {
		walDirectory = persistentDirectory;
	}

	@Override
	public void setTransientDirectory(String transientDirectory) {
		mapDirectory = transientDirectory;
	}

	@Override
	public void setCallingProcessor(AbstractProcessor processor) {
		this.processor = processor;
	}

	@Override
	public void setEventFactory(EventFactory factory) {
		this.factory = factory;
	}

	/**
	 * For simple rocksdb testing
	 * 
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		RocksDBWALService wal = new RocksDBWALService();
		wal.setPersistentDirectory("target/wal");
		wal.setTransientDirectory("target/mem");
		wal.start();
	}
}