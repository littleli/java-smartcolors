package org.smartcolors;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;

import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptOpCodes;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.testing.FakeTxBuilder;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ColorScannerTest {
	public static final Script EMPTY_SCRIPT = new Script(new byte[0]);

	private NetworkParameters params;
	private ColorScanner scanner;
	private Transaction genesisTx;
	private TransactionOutPoint genesisOutPoint;
	private MemoryBlockStore blockStore;
	private StoredBlock genesisBlock;
	private ColorProof proof;
	private ColorDefinition def;
	private Script opReturnScript;

	@Before
	public void setUp() throws Exception {
		params = NetworkParameters.fromID(NetworkParameters.ID_REGTEST);
		blockStore = new MemoryBlockStore(params);
		genesisTx = new Transaction(params);
		genesisTx.addInput(Sha256Hash.ZERO_HASH, 0, EMPTY_SCRIPT);
		ScriptBuilder ret = new ScriptBuilder();
		ret.op(ScriptOpCodes.OP_RETURN);
		ret.data(ColorProof.SMART_ASSET_MARKER.getBytes());
		opReturnScript = ret.build();
		genesisTx.addOutput(Utils.makeAssetCoin(10), new Script(new byte[0]));
		genesisTx.addOutput(Coin.ZERO, opReturnScript);
		genesisBlock = FakeTxBuilder.createFakeBlock(blockStore, genesisTx).storedBlock;
		genesisOutPoint = new TransactionOutPoint(params, 0, genesisTx);
		TxOutGenesisPoint genesis = new TxOutGenesisPoint(params, genesisOutPoint);
		SortedSet<GenesisPoint> points = Sets.newTreeSet();
		points.add(genesis);
		Map<String, String> metadata = Maps.newHashMap();
		metadata.put("name", "widgets");
		def = new ColorDefinition(points, metadata);
		proof = new ColorProof(def);
		scanner = new ColorScanner();
		scanner.addProof(proof);
	}

	@Test
	public void testGetColors() {
		Set<ColorDefinition> colors = scanner.getColorDefinitions();
		assertEquals(1, colors.size());
		assertEquals(def, colors.iterator().next());
	}

	@Test
	public void testBloomFilter() throws Exception {
		// Genesis
		assertEquals(1, scanner.getBloomFilterElementCount());
		assertTrue(getBloomFilter().contains(org.bitcoinj.core.Utils.HEX.decode("534d415254415353")));
	}

	@Test
	public void testGetNetAssetChangeUnknown() {
		Wallet wallet = new Wallet(params) {
			@Override
			public boolean isPubKeyMine(byte[] pubkey) {
				return true;
			}
		};
		Transaction tx2 = new Transaction(params);
		tx2.addInput(genesisTx.getOutput(0));
		tx2.addOutput(Utils.makeAssetCoin(5), ScriptBuilder.createOutputScript(new ECKey()));
		tx2.addOutput(Coin.ZERO, opReturnScript);
		Map<ColorDefinition, Long> res = scanner.getNetAssetChange(tx2, wallet);
		Map<ColorDefinition, Long> expected = Maps.newHashMap();
		expected.put(ColorDefinition.UNKNOWN, 5L);
		assertEquals(expected, res);
	}

	@Test
	public void testGetNetAssetChange() {
		final ECKey myKey = new ECKey();
		scanner.receiveFromBlock(genesisTx, FakeTxBuilder.createFakeBlock(blockStore, genesisTx).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		Wallet wallet = new Wallet(params) {
			@Override
			public boolean isPubKeyMine(byte[] pubkey) {
				return Arrays.equals(pubkey, myKey.getPubKey());
			}
		};

		Transaction tx2 = new Transaction(params);
		tx2.addInput(genesisTx.getOutput(0));
		tx2.addOutput(Utils.makeAssetCoin(5), ScriptBuilder.createOutputScript(myKey));
		tx2.addOutput(Coin.ZERO, opReturnScript);
		scanner.receiveFromBlock(tx2, FakeTxBuilder.createFakeBlock(blockStore, tx2).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		wallet.receiveFromBlock(tx2, FakeTxBuilder.createFakeBlock(blockStore, tx2).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		Map<ColorDefinition, Long> expected = Maps.newHashMap();
		Map<ColorDefinition, Long> res = scanner.getNetAssetChange(tx2, wallet);
		expected.put(def, 5L);
		assertEquals(expected, res);


		Transaction tx3 = new Transaction(params);
		tx3.addInput(tx2.getOutput(0));
		tx3.addOutput(Utils.makeAssetCoin(2), ScriptBuilder.createOutputScript(myKey));
		tx3.addOutput(Utils.makeAssetCoin(3), ScriptBuilder.createOutputScript(new ECKey()));
		tx3.addOutput(Coin.ZERO, opReturnScript);
		scanner.receiveFromBlock(tx3, FakeTxBuilder.createFakeBlock(blockStore, tx3).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		wallet.receiveFromBlock(tx3, FakeTxBuilder.createFakeBlock(blockStore, tx3).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);

		expected.clear();
		res = scanner.getNetAssetChange(tx3, wallet);
		expected.put(def, -3L);
		assertEquals(expected, res);
	}

	@Test
	public void testGetTransactionWithUnknownAsset() throws ExecutionException, InterruptedException {
		final ECKey myKey = new ECKey();
		scanner.receiveFromBlock(genesisTx, FakeTxBuilder.createFakeBlock(blockStore, genesisTx).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		Wallet wallet = new Wallet(params) {
			@Override
			public boolean isPubKeyMine(byte[] pubkey) {
				return Arrays.equals(pubkey, myKey.getPubKey());
			}
		};

		Transaction tx2 = new Transaction(params);
		tx2.addInput(genesisTx.getOutput(0));
		tx2.addOutput(Utils.makeAssetCoin(5), ScriptBuilder.createOutputScript(myKey));
		tx2.addOutput(Coin.ZERO, opReturnScript);
		wallet.receiveFromBlock(tx2, FakeTxBuilder.createFakeBlock(blockStore, tx2).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		ListenableFuture<Transaction> future = scanner.getTransactionWithKnownAssets(tx2, wallet);
		assertFalse(future.isDone());
		scanner.receiveFromBlock(tx2, FakeTxBuilder.createFakeBlock(blockStore, tx2).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		assertTrue(future.isDone());
		assertEquals(tx2, future.get());
	}

	@Test
	public void testGetTransactionWithUnknownAssetFail() throws ExecutionException, InterruptedException {
		final ECKey myKey = new ECKey();
		scanner.receiveFromBlock(genesisTx, FakeTxBuilder.createFakeBlock(blockStore, genesisTx).storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		Wallet wallet = new Wallet(params) {
			@Override
			public boolean isPubKeyMine(byte[] pubkey) {
				return Arrays.equals(pubkey, myKey.getPubKey());
			}
		};

		Transaction tx2 = new Transaction(params);
		tx2.addInput(genesisTx.getOutput(0));
		tx2.addOutput(Utils.makeAssetCoin(5), ScriptBuilder.createOutputScript(myKey));
		tx2.addOutput(Coin.ZERO, opReturnScript);
		StoredBlock storedBlock = FakeTxBuilder.createFakeBlock(blockStore, tx2).storedBlock;
		wallet.receiveFromBlock(tx2, storedBlock, AbstractBlockChain.NewBlockType.BEST_CHAIN, 0);
		ListenableFuture<Transaction> future = scanner.getTransactionWithKnownAssets(tx2, wallet);
		assertFalse(future.isDone());
		scanner.notifyNewBestBlock(storedBlock);
		assertTrue(future.isDone());
		try {
			future.get();
			fail();
		} catch (ExecutionException ex) {
			assertEquals(ColorScanner.ScanningException.class, ex.getCause().getClass());
		}
	}

	private BloomFilter getBloomFilter() {
		return scanner.getBloomFilter(10, 1e-12, (long) (Math.random() * Long.MAX_VALUE));
	}
}