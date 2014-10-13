package org.smartcolors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;

import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.AbstractPeerEventListener;
import org.bitcoinj.core.BlockChainListener;
import org.bitcoinj.core.BloomFilter;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerFilterProvider;
import org.bitcoinj.core.ScriptException;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VerificationException;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.WalletTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A blockchain and peer listener that keeps a set of color trackers updated with blockchain events.
 */
public class ColorScanner implements PeerFilterProvider, BlockChainListener {
	private static final Logger log = LoggerFactory.getLogger(ColorScanner.class);

	private final AbstractPeerEventListener peerEventListener;
	Set<ColorProof> proofs = Sets.newHashSet();
	protected final ReentrantLock lock = Threading.lock("colorScanner");
	SetMultimap<Sha256Hash, SortedTransaction> mapBlockTx = TreeMultimap.create();
	Map<Sha256Hash, Transaction> pending = Maps.newHashMap();

	public ColorScanner() {
		peerEventListener = new AbstractPeerEventListener() {
			@Override
			public void onTransaction(Peer peer, Transaction t) {
				pending.put(t.getHash(), t);
			}

			@Override
			public void onPeerConnected(Peer peer, int peerCount) {
				log.info("Peer connected {}", peer);
				peer.addEventListener(this);
			}
		};
	}

	public AbstractPeerEventListener getPeerEventListener() {
		return peerEventListener;
	}

	/** Add a color to the set of tracked colors */
	public void addProof(ColorProof proof) {
		proofs.add(proof);
	}

	@Override
	public void notifyNewBestBlock(StoredBlock block) throws VerificationException {
	}

	@Override
	public void reorganize(StoredBlock splitPoint, List<StoredBlock> oldBlocks, List<StoredBlock> newBlocks) throws VerificationException {
		log.info("reorganize {} -> {}", newBlocks.size(), oldBlocks.size());
		// Remove transactions from old blocks
		for (ColorProof proof : proofs) {
			blocks:
			for (StoredBlock block : oldBlocks) {
				for (SortedTransaction tx : mapBlockTx.get(block.getHeader().getHash())) {
					if (proof.contains(tx.tx)) {
						proof.undo(tx.tx);
						// Transactions that are topologically later are automatically removed by
						// ColorProof.undo, so we can break here.
						break blocks;
					}
				}
			}
		}

		// Add transactions from new blocks
		for (ColorProof proof : proofs) {
			for (StoredBlock block : newBlocks) {
				for (SortedTransaction tx : mapBlockTx.get(block.getHeader().getHash())) {
					if (proof.isTransactionRelevant(tx.tx)) {
						proof.add(tx.tx);
					}
				}
			}
		}
	}

	@Override
	public boolean isTransactionRelevant(Transaction tx) throws ScriptException {
		log.info("isRelevant {}", tx.getHash());
		return true; // Always relevant with OP_RETURN marker scanning
	}

	@Override
	public void receiveFromBlock(Transaction tx, StoredBlock block, AbstractBlockChain.NewBlockType blockType, int relativityOffset) throws VerificationException {
		receive(tx, block, blockType, relativityOffset);
	}

	private boolean receive(Transaction tx, StoredBlock block, AbstractBlockChain.NewBlockType blockType, int relativityOffset) {
		log.info("receive {} {}", tx, relativityOffset);
		mapBlockTx.put(block.getHeader().getHash(), new SortedTransaction(tx, relativityOffset));
		if (blockType == AbstractBlockChain.NewBlockType.BEST_CHAIN) {
			for (ColorProof proof : proofs) {
				if (proof.isTransactionRelevant(tx)) {
					proof.add(tx);
				}
			}
		}
		return true; // Always relevant with OP_RETURN marker scanning
	}

	@Override
	public boolean notifyTransactionIsInBlock(Sha256Hash txHash, StoredBlock block, AbstractBlockChain.NewBlockType blockType, int relativityOffset) throws VerificationException {
		Transaction tx = pending.get(txHash);
		log.info("in block {} {}", tx, relativityOffset);
		return (tx != null) && receive(tx, block, blockType, relativityOffset);
	}

	@Override
	public long getEarliestKeyCreationTime() {
		long creationTime = Long.MAX_VALUE;
		for (ColorProof proof : proofs) {
			creationTime = Math.min(creationTime, proof.getCreationTime());
		}
		return creationTime;
	}

	@Override
	public int getBloomFilterElementCount() {
		int count = 0;
		for (ColorProof proof : proofs) {
			count += proof.getBloomFilterElementCount();
		}
		return count;
	}

	@Override
	public BloomFilter getBloomFilter(int size, double falsePositiveRate, long nTweak) {
		BloomFilter filter = new BloomFilter(size, falsePositiveRate, nTweak);
		for (ColorProof proof : proofs) {
			proof.updateBloomFilter(filter);
		}
		return filter;
	}

	@Override
	public boolean isRequiringUpdateAllBloomFilter() {
		return true;
	}

	@Override
	public Lock getLock() {
		return lock;
	}

	/**
	 * Get the net movement of assets caused by the transaction.
	 *
	 * <p>If we notice an output that is marked as carrying color, but we don't know what asset
	 * it is, it will be marked as {@link org.smartcolors.ColorDefinition#UNKNOWN}</p>
	 */
	public Map<ColorDefinition, Long> getNetAssetChange(Transaction tx, Wallet wallet) {
		HashMap<ColorDefinition, Long> res = Maps.newHashMap();
		outs: for (TransactionOutput out : getColoredOutputs(tx)) {
			if (out.isMine(wallet)) {
				for (ColorProof proof: proofs) {
					Long value = proof.getOutputs().get(out.getOutPointFor());
					if (value != null) {
						Long existing = res.get(proof.getDefinition());
						if (existing != null)
							value = existing + value;
						res.put(proof.getDefinition(), value);
						continue outs;
					}
				}
				// Unknown asset on this output
				Long value = SmartColors.removeMsbdropValuePadding(out.getValue().getValue());
				Long existing = res.get(ColorDefinition.UNKNOWN);
				if (existing != null)
					value = value + existing;
				res.put(ColorDefinition.UNKNOWN, value);
			}
		}
		inps: for (TransactionInput inp: tx.getInputs()) {
			if (isInputMine(inp, wallet)) {
				for (ColorProof proof : proofs) {
					Long value = proof.getOutputs().get(inp.getOutpoint());
					if (value != null) {
						Long existing = res.get(proof.getDefinition());
						if (existing != null)
							value = existing - value;
						res.put(proof.getDefinition(), value);
						continue inps;
					}
				}
			}
		}
		return res;
	}

	private boolean isInputMine(TransactionInput input, Wallet wallet) {
		TransactionOutPoint outpoint = input.getOutpoint();
		TransactionOutput connected = getConnected(outpoint, wallet.getTransactionPool(WalletTransaction.Pool.UNSPENT));
		if (connected == null)
			connected = getConnected(outpoint, wallet.getTransactionPool(WalletTransaction.Pool.SPENT));
		if (connected == null)
			connected = getConnected(outpoint, wallet.getTransactionPool(WalletTransaction.Pool.PENDING));
		if (connected == null)
			return false;
		// The connected output may be the change to the sender of a previous input sent to this wallet. In this
		// case we ignore it.
		return connected.isMine(wallet);
	}

	private TransactionOutput getConnected(TransactionOutPoint outpoint, Map<Sha256Hash, Transaction> transactions) {
		Transaction tx = transactions.get(outpoint.getHash());
		if (tx == null)
			return null;
		return tx.getOutputs().get((int) outpoint.getIndex());
	}

	private List<TransactionOutput> getColoredOutputs(Transaction tx) {
		long mask = ~0;
		for (TransactionInput input: tx.getInputs()) {
			mask = mask & input.getSequenceNumber();
		}

		List<TransactionOutput> outputs = Lists.newArrayList();
		for (TransactionOutput output: tx.getOutputs()) {
			if ((mask & 1) == 1) {
				outputs.add(output);
			}
			mask = mask >> 1;
		}

		return outputs;
	}
}
