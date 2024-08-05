package com.interop.flows.internal

import com.r3.corda.evminterop.dto.TransactionReceipt
import com.r3.corda.evminterop.dto.encoded
import com.r3.corda.evminterop.services.IERC20
import com.r3.corda.evminterop.services.IWeb3
import com.r3.corda.evminterop.services.IdentityServiceProvider
import com.r3.corda.evminterop.services.evmInterop
import com.r3.corda.evminterop.workflows.GenericAssetSchemaV1
import com.r3.corda.evminterop.workflows.IssueGenericAssetFlow
import com.r3.corda.evminterop.workflows.UnsecureRemoteEvmIdentityFlow
import com.r3.corda.evminterop.workflows.eth2eth.Erc20TransferFlow
import com.r3.corda.evminterop.workflows.eth2eth.GetBlockFlow
import com.r3.corda.evminterop.workflows.eth2eth.GetBlockReceiptsFlow
import com.r3.corda.evminterop.workflows.swap.ClaimCommitment
import com.r3.corda.evminterop.workflows.swap.CommitWithTokenFlow
import com.r3.corda.interop.evm.common.trie.PatriciaTrie
import com.r3.corda.interop.evm.common.trie.SimpleKeyValueStore
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowExternalOperation
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.FlowStateMachineHandle
import net.corda.core.internal.concurrent.flatMap
import net.corda.core.internal.concurrent.map
import net.corda.core.internal.packageName
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.internal.chooseIdentity
import net.corda.testing.node.*
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.newContext
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Assert
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.web3j.rlp.RlpEncoder
import org.web3j.rlp.RlpString
import org.web3j.utils.Numeric
import java.lang.management.ManagementFactory
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.*
import java.util.function.Supplier

/**
 * ~/evm-interop-workflows testnet setup
 */
abstract class TestNetSetup(
        val jsonRpcEndpoint: String = "http://localhost:8545",
        val chainId: Long = 1337
) {
    protected val oneEth            = BigInteger("1000000000000000000")
    protected val oneHundredEth     = BigInteger("100000000000000000000")
    protected val twoHundredEth     = BigInteger("200000000000000000000")
    protected val oneThousandEth    = BigInteger("1000000000000000000000")
    protected val oneMillionEth     = BigInteger("1000000000000000000000000")

    protected var aliceAddress = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    protected var bobAddress = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC"
    protected var charlieAddress = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"

    private val alicePrivateKey = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val bobPrivateKey = "0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a"
    private val charliePrivateKey = "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"

    protected val evmDeployerAddress = "0x5067457698Fd6Fa1C6964e416b3f42713513B3dD"
    protected val protocolAddress = "0x70e0bA845a1A0F2DA3359C97E0285013525FFC49"
    protected val goldTokenDeployAddress = "0x5FbDB2315678afecb367f032d93F642f64180aa3"
    protected val silverTokenDeployAddress = "0xc6e7DF5E7b4f2A278906862b61205850344D4e7d"

    private lateinit var executor: ExecutorService

    protected val network: MockNetwork by lazy {
        mockNetwork()
    }

    protected val alice: StartedMockNode by lazy {
        createNode(network, "O=Alice, L=London, C=GB").also {
            runFlow(it, UnsecureRemoteEvmIdentityFlow(alicePrivateKey, jsonRpcEndpoint, chainId, protocolAddress, evmDeployerAddress))
        }
    }
    protected val bob: StartedMockNode by lazy {
        createNode(network, "O=Bob, L=San Francisco, C=US").also {
            runFlow(it, UnsecureRemoteEvmIdentityFlow(bobPrivateKey, jsonRpcEndpoint, chainId, protocolAddress, evmDeployerAddress))
        }
    }
    protected val charlie: StartedMockNode by lazy {
        createNode(network, "O=Charlie, L=Mumbai, C=IN").also {
            runFlow(it, UnsecureRemoteEvmIdentityFlow(charliePrivateKey, jsonRpcEndpoint, chainId, protocolAddress, evmDeployerAddress))
        }
    }
    protected val notary: StartedMockNode by lazy {
        network.defaultNotaryNode
    }

    private fun createNode(
        network: MockNetwork,
        name: String
    ): StartedMockNode {
        val nodeName = CordaX500Name.parse(name)

        return network.createNode(MockNodeParameters(legalName = nodeName))
    }

    private fun networkSetup() {
        try {
            aliceAddress = alice.services.evmInterop().signerAddress()
            bobAddress = bob.services.evmInterop().signerAddress()
            charlieAddress = charlie.services.evmInterop().signerAddress()

        } catch (ex: Exception) {
            println("Failed to start nodes, error:\n\n$ex")
            network.stopNodes()
            throw ex
        }

        onNetworkSetup()
    }

    private fun mockNetwork() : MockNetwork {
        return MockNetwork(
            listOf(
                "com.interop.flows",
                "com.r3.corda.evminterop.services",
                "com.r3.corda.evminterop.workflows",
                "com.r3.corda.evminterop.workflows.eth2eth",
                "com.r3.corda.evminterop.workflows.swap",
                "com.r3.corda.evminterop.workflows.token",
                //"com.r3.corda.evminterop",
                "com.r3.corda.evminterop.states.swap",
                "com.r3.corda.evminterop.dto",
                "com.r3.corda.evminterop.contracts.swap",
                GenericAssetSchemaV1::class.packageName
            ),
            threadPerNode = false,
            networkSendManuallyPumped = false,
            notarySpecs = listOf(
                MockNetworkNotarySpec(CordaX500Name("Notary","London","GB"), validating = false)
            ))
    }

    protected open fun onNetworkSetup() {}

    private fun networkTeardown() {
        network.stopNodes()
    }

    @Before fun setup() {
        executor = Executors.newCachedThreadPool()
        networkSetup()
    }
    @After fun tearDown() {
        networkTeardown()
        executor.shutdown()
    }

    protected fun StartedMockNode.erc20(tokenAddress: String): IERC20 {
        return services.cordaService(IdentityServiceProvider::class.java).erc20(tokenAddress, toParty().owningKey)
    }

    private fun StartedMockNode.web3j(): IWeb3 {
        return services.cordaService(IdentityServiceProvider::class.java).web3(toParty().owningKey)
    }

    protected fun StartedMockNode.web3(): IWeb3 = this.web3j()
    protected fun StartedMockNode.goldToken(): IERC20 = this.erc20(goldTokenDeployAddress)
    protected fun StartedMockNode.silverToken(): IERC20 = this.erc20(silverTokenDeployAddress)
    protected fun <V : Any> FlowExternalOperation<V>.get(): V = this.execute(UUID.randomUUID().toString())
    protected fun StartedMockNode.toParty() = this.info.chooseIdentity()

    internal fun setCordaClock(futureInstant: Instant){
        setOf(alice, bob, charlie).forEach {
            (it.services.clock as TestClock).setTo(futureInstant)
        }
    }

    internal fun setEvmClock(futureInstant: Instant){
        alice.web3j().evmSetNextBlockTimestamp(futureInstant.epochSecond.toBigInteger())
    }

    protected fun<T> runFlow(node: StartedMockNode, logic: FlowLogic<T>) : T
    {
        network.runNetwork()
        try {
            return node.transaction {
                val future = node.startFlow(logic)
                network.runNetwork()
                future.getOrThrow(Duration.ofMinutes(2))
            }
        } catch (e: TimeoutException) {
            val threadInfo = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)
            threadInfo.forEach { println(it) }
            // REVIEW: Workaround while investigating `FinalityFlow may become unresponsive in mock network with some flows`
            throw AssumptionViolatedException("TIMEOUT: Inconclusive test, skipping", e)
        } finally {
            network.runNetwork()
        }
    }

    // Helper function to transfer an EVM asset and produce a merkle proof from the transaction's receipt.
    protected fun transferAndProve(amount: BigInteger, senderNode: StartedMockNode, recipientAddress: String) : Triple<TransactionReceipt, ByteArray, SimpleKeyValueStore> {

        // create an ERC20 Transaction from alice to bob that will emit a Transfer event for the given amount
        val transactionReceipt: TransactionReceipt = runFlow(senderNode,
            Erc20TransferFlow(goldTokenDeployAddress, recipientAddress, amount)
        )

        // get the block that mined the ERC20 `Transfer` Transaction
        val block = runFlow(senderNode,
            GetBlockFlow(transactionReceipt.blockNumber, true)
        )

        // get all transaction receipts from the block that mined the ERC20 `Transfer` Transaction
        val receipts = runFlow(senderNode,
            GetBlockReceiptsFlow(transactionReceipt.blockNumber)
        )

        // Build the Patricia Trie from the Block receipts and verify it's valid
        val trie = PatriciaTrie()
        for(receipt in receipts) {
            trie.put(
                RlpEncoder.encode(RlpString.create(Numeric.toBigInt(receipt.transactionIndex!!).toLong())),
                receipt.encoded()
            )
        }
        Assert.assertEquals(block.receiptsRoot, Numeric.toHexString(trie.root.hash))

        // generate a proof for the transaction receipt that belong to the ERC20 transfer transaction
        val transferKey = RlpEncoder.encode(RlpString.create(Numeric.toBigInt(transactionReceipt.transactionIndex!!).toLong()))
        val transactionProof = trie.generateMerkleProof(transferKey) as SimpleKeyValueStore

        return Triple(transactionReceipt, transferKey, transactionProof)
    }

    protected fun commitAndClaim(
            transactionId: SecureHash,
            amount: BigInteger,
            senderNode: StartedMockNode,
            recipientAddress: String,
            threshold: BigInteger,
            signers: List<String>
    ) : Triple<TransactionReceipt, ByteArray, SimpleKeyValueStore> {

        val commitTxReceipt: TransactionReceipt = runFlow(senderNode,
                CommitWithTokenFlow(transactionId, goldTokenDeployAddress, amount, recipientAddress, threshold, signers)
        )

        val claimTxReceipt: TransactionReceipt = runFlow(senderNode,
                ClaimCommitment(transactionId)
        )

        // get the block that mined the ERC20 `Transfer` Transaction
        val block = runFlow(senderNode,
                GetBlockFlow(claimTxReceipt.blockNumber, true)
        )

        // get all transaction receipts from the block that mined the ERC20 `Transfer` Transaction
        val receipts = runFlow(senderNode,
                GetBlockReceiptsFlow(claimTxReceipt.blockNumber)
        )

        // Build the Patricia Trie from the Block receipts and verify it's valid
        val trie = PatriciaTrie()
        for(receipt in receipts) {
            trie.put(
                    RlpEncoder.encode(RlpString.create(Numeric.toBigInt(receipt.transactionIndex!!).toLong())),
                    receipt.encoded()
            )
        }
        Assert.assertEquals(block.receiptsRoot, Numeric.toHexString(trie.root.hash))

        // generate a proof for the transaction receipt that belong to the ERC20 transfer transaction
        val claimKey = RlpEncoder.encode(RlpString.create(Numeric.toBigInt(claimTxReceipt.transactionIndex!!).toLong()))
        val transactionProof = trie.generateMerkleProof(claimKey) as SimpleKeyValueStore

        return Triple(claimTxReceipt, claimKey, transactionProof)
    }
}
