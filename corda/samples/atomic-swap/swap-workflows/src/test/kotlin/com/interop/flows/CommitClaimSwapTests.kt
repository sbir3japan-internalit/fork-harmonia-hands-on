package com.interop.flows

import com.interop.flows.internal.TestNetSetup
import com.r3.corda.evminterop.SwapVaultEventEncoder
import com.r3.corda.evminterop.dto.TransactionReceipt
import com.r3.corda.evminterop.services.swap.DraftTxService
import com.r3.corda.evminterop.workflows.GenericAssetSchemaV1
import com.r3.corda.evminterop.workflows.GenericAssetState
import com.r3.corda.evminterop.workflows.IssueGenericAssetFlow
import com.r3.corda.evminterop.workflows.swap.*
import net.corda.core.contracts.OwnableState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.internal.chooseIdentity
import org.bouncycastle.asn1.x500.style.RFC4519Style.owner
import org.junit.Assert
import org.junit.Test
import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.Utils
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.*
import kotlin.test.assertEquals

class CommitClaimSwapTests : TestNetSetup() {

    private val amount = 1.toBigInteger()

    // 1. alice bob agreement (not in scope)
    // 2. bob drafts a transaction
    // 3. alice agree by committing an EVM asset
    // 4. bob notarizes transaction
    // 5. alice claim the EVM asset in favor of bob
    // 6. bob claims the Corda locked asset by presenting evm proofs
    @Test
    fun `bob can unlock corda asset by asynchronous collection of block signatures`() {
        val assetName = UUID.randomUUID().toString()

        // Create Corda asset owned by Bob
        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        // Prepare the generic `claim / revert` event expectation.
        // Note that this is not the encoded event but the event encoder. It does not include the draft transaction hash,
        // which is only known after the draft transaction is created. Therefore, the encoder builds the encoded event
        // only when a new transaction consumes the draft transaction outputs, using their state-ref to build the full
        // encoded event.
        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        // Draft the Corda Asset transfer that can be transferred to the recipient or reverted to the owner if valid
        // EVM event proofs are presented for the claim / revert transaction events from the expected protocol address
        // and draft transaction hash (swap id).
        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Sign the draft transaction. In real use cases, this only happens after the counterparty (i.e.: alice) signals
        // the acceptance of the draft transaction and the willing to continue with the swap with a commit of the
        // counterparty EVM asset.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // counterparty (alice, EVM) commits the asset and claims it in favour of the recipient (bob, EVM address)
        val (txReceipt, leafKey, merkleProof) = commitAndClaim(
            draftTxHash, amount, alice, bobAddress, BigInteger.ONE, listOf(charlieAddress)
        )

        // bob collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(bob, CollectBlockSignaturesFlow(draftTxHash, txReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(bob,
            UnlockAssetFlow(
                stx.tx.id,
                txReceipt.blockNumber,
                Numeric.toBigInt(txReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset is now owned by Alice and not anymore from Bob
        Assert.assertEquals(
            alice.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that bob can't see the locked asset anymore
        assert(bob.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    // 1. alice bob agreement (not in scope)
    // 2. bob drafts a transaction
    // 3. alice agree by committing an EVM asset
    // 4. bob notarizes transaction
    // 5. alice claim the EVM asset in favor of bob
    // 6. alice claims the Corda locked asset by presenting evm proofs
    @Test
    fun `alice can unlock corda asset by asynchronous collection of block signatures`() {
        val assetName = UUID.randomUUID().toString()

        // Create Corda asset owned by Bob
        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        // Prepare the generic `claim / revert` event expectation.
        // Note that this is not the encoded event but the event encoder. It does not include the draft transaction hash,
        // which is only known after the draft transaction is created. Therefore, the encoder builds the encoded event
        // only when a new transaction consumes the draft transaction outputs, using their state-ref to build the full
        // encoded event.
        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        // Draft the Corda Asset transfer that can be transferred to the recipient or reverted to the owner if valid
        // EVM event proofs are presented for the claim / revert transaction events from the expected protocol address
        // and draft transaction hash (swap id).
        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Sign the draft transaction. In real use cases, this only happens after the counterparty (i.e.: alice) signals
        // the acceptance of the draft transaction and the willing to continue with the swap with a commit of the
        // counterparty EVM asset.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // counterparty (alice, EVM) commits the asset and claims it in favour of the recipient (bob, EVM address)
        val (txReceipt, leafKey, merkleProof) = commitAndClaim(
            draftTxHash, amount, alice, bobAddress, BigInteger.ONE, listOf(charlieAddress)
        )

        // alice collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(alice, CollectBlockSignaturesFlow(draftTxHash, txReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(alice,
            UnlockAssetFlow(
                stx.tx.id,
                txReceipt.blockNumber,
                Numeric.toBigInt(txReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset is now owned by Alice and not anymore from Bob
        Assert.assertEquals(
            alice.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that bob can't see the locked asset anymore
        assert(bob.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    // 1. alice bob agreement (not in scope)
    // 2. bob drafts a transaction
    // 3. alice agree by committing an EVM asset
    // 4. bob notarizes transaction
    // 5. bob claim the EVM asset in his favor by presenting proofs
    // 6. alice claims the Corda locked asset by presenting evm proofs
    @Test
    fun `bob can transfer evm asset by asynchronous collection of notarisation signatures`() {
        val assetName = UUID.randomUUID().toString()

        // Create Corda asset owned by Bob
        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        // Prepare the generic `claim / revert` event expectation.
        // Note that this is not the encoded event but the event encoder. It does not include the draft transaction hash,
        // which is only known after the draft transaction is created. Therefore, the encoder builds the encoded event
        // only when a new transaction consumes the draft transaction outputs, using their state-ref to build the full
        // encoded event.
        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        // Draft the Corda Asset transfer that can be transferred to the recipient or reverted to the owner if valid
        // EVM event proofs are presented for the claim / revert transaction events from the expected protocol address
        // and draft transaction hash (swap id).
        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Alice commits her asset to the protocol contract
        val commitTxReceipt: TransactionReceipt = runFlow(alice, CommitWithTokenFlow(draftTxHash, goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress)))

        // Sign the draft transaction.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // bob collects evm signatures from bob and charlie
        runFlow(bob, CollectNotarizationSignaturesFlow(draftTxHash, true))

        // collect the EVM verifiable signatures that attest that the draft transaction was signed by the notary
        val signatures = bob.services.cordaService(DraftTxService::class.java).notarizationProofs(draftTxHash)

        // Bob can claim Alice's EVM committed asset
        val txReceipt: TransactionReceipt = runFlow(bob, ClaimCommitmentWithSignatures(draftTxHash, signatures))

        // alice collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(alice, CollectBlockSignaturesFlow(draftTxHash, txReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(alice,
            UnlockAssetFlow(
                stx.tx.id,
                txReceipt.blockNumber,
                Numeric.toBigInt(txReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset is now owned by Alice and not anymore from Bob
        Assert.assertEquals(
            alice.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that bob can't see the locked asset anymore
        assert(bob.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    // 1. alice bob agreement (not in scope)
    // 2. bob drafts a transaction
    // 3. alice agree by committing an EVM asset
    // 4. bob notarizes transaction
    // 5. bob claim the EVM asset in his favor by presenting proofs
    // 6. bob claims the Corda locked asset in favour of alice by presenting evm proofs
    @Test
    fun `bob can transfer evm and corda assets to recipients by asynchronous collection of notarisation and block signatures`() {
        val assetName = UUID.randomUUID().toString()

        // Create Corda asset owned by Bob
        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        // Prepare the generic `claim / revert` event expectation.
        // Note that this is not the encoded event but the event encoder. It does not include the draft transaction hash,
        // which is only known after the draft transaction is created. Therefore, the encoder builds the encoded event
        // only when a new transaction consumes the draft transaction outputs, using their state-ref to build the full
        // encoded event.
        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        // Draft the Corda Asset transfer that can be transferred to the recipient or reverted to the owner if valid
        // EVM event proofs are presented for the claim / revert transaction events from the expected protocol address
        // and draft transaction hash (swap id).
        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Alice commits her asset to the protocol contract
        val commitTxReceipt: TransactionReceipt = runFlow(alice, CommitWithTokenFlow(draftTxHash, goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress)))

        // Sign the draft transaction.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // alice collects evm signatures from bob and charlie
        runFlow(bob, CollectNotarizationSignaturesFlow(draftTxHash, true))

        // collect the EVM verifiable signatures that attest that the draft transaction was signed by the notary
        val signatures = bob.services.cordaService(DraftTxService::class.java).notarizationProofs(draftTxHash)

        // Bob can claim Alice's EVM committed asset
        val txReceipt: TransactionReceipt = runFlow(bob, ClaimCommitmentWithSignatures(draftTxHash, signatures))

        // bob collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(bob, CollectBlockSignaturesFlow(draftTxHash, txReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(bob,
            UnlockAssetFlow(
                stx.tx.id,
                txReceipt.blockNumber,
                Numeric.toBigInt(txReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset is now owned by Alice and not anymore from Bob
        Assert.assertEquals(
            alice.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that bob can't see the locked asset anymore
        assert(bob.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    @Test
    fun `alice can revert her commit of the EVM asset`() {
        val amount = 1.toBigInteger()
        val transactionId = SecureHash.randomSHA256()
        val balanceBefore = alice.goldToken().balanceOf(aliceAddress).also {
            network.runNetwork()
        }.get()

        val commitTxReceipt: TransactionReceipt = runFlow(alice, CommitWithTokenFlow(transactionId, goldTokenDeployAddress, amount, bobAddress, amount, emptyList()))

        val balanceAfterCommit = alice.goldToken().balanceOf(aliceAddress).also {
            network.runNetwork()
        }.get()

        val revertTxReceipt: TransactionReceipt = runFlow(alice, RevertCommitment(transactionId))

        val balanceAfterRevert = alice.goldToken().balanceOf(aliceAddress).also {
            network.runNetwork()
        }.get()

        assertEquals(balanceBefore, balanceAfterRevert)
        assertEquals(balanceBefore, amount + balanceAfterCommit)
    }

    @Test
    fun `bob can revert his notarized transaction presenting revert proof`() {
        val amount = 1.toBigInteger()
        val assetName = UUID.randomUUID().toString()

        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Alice commits her asset to the protocol contract
        val commitTxReceipt: TransactionReceipt = runFlow(alice, CommitWithTokenFlow(draftTxHash, goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress)))

        // Sign the draft transaction.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // Bob can claim Alice's EVM committed asset
        val revertReceipt: TransactionReceipt = runFlow(alice, RevertCommitment(draftTxHash))

        // bob collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(bob, CollectBlockSignaturesFlow(draftTxHash, revertReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(bob,
            RevertAssetFlow(
                stx.tx.id,
                revertReceipt.blockNumber,
                Numeric.toBigInt(revertReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset ownership is now reverted to Bob
        Assert.assertEquals(
            bob.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that alice can't see the locked asset anymore
        assert(alice.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    @Test
    fun `alice can revert bob's notarized transaction presenting revert proof`() {
        val amount = 1.toBigInteger()
        val assetName = UUID.randomUUID().toString()

        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Alice commits her asset to the protocol contract
        val commitTxReceipt: TransactionReceipt = runFlow(alice, CommitWithTokenFlow(draftTxHash, goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress)))

        // Sign the draft transaction.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // Bob can claim Alice's EVM committed asset
        val revertReceipt: TransactionReceipt = runFlow(alice, RevertCommitment(draftTxHash))

        // bob collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(alice, CollectBlockSignaturesFlow(draftTxHash, revertReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(alice,
            RevertAssetFlow(
                stx.tx.id,
                revertReceipt.blockNumber,
                Numeric.toBigInt(revertReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset ownership is now reverted to Bob
        Assert.assertEquals(
            bob.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that alice can't see the locked asset anymore
        assert(alice.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    @Test
    fun `bob can revert alice commitment and his notarized transaction presenting revert proof`() {
        val amount = 1.toBigInteger()
        val assetName = UUID.randomUUID().toString()

        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        val swapVaultEventEncoder = SwapVaultEventEncoder.create(
            chainId = BigInteger.valueOf(1337),
            protocolAddress = protocolAddress,
            owner = aliceAddress,
            recipient = bobAddress,
            amount = amount,
            tokenId = BigInteger.ZERO,
            tokenAddress = goldTokenDeployAddress,
            signaturesThreshold = BigInteger.ONE,
            signers = listOf(charlieAddress) // same as validators but the EVM identity instead
        )

        val draftTxHash = runFlow(bob, DraftAssetSwapFlow(
            assetTx.txhash,
            assetTx.index,
            alice.toParty(),
            alice.services.networkMapCache.notaryIdentities.first(),
            listOf(charlie.toParty() as AbstractParty),
            1,
            swapVaultEventEncoder
        ))

        // Alice commits her asset to the protocol contract
        val commitTxReceipt: TransactionReceipt = runFlow(alice,
            CommitWithTokenFlow(draftTxHash, goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress)

        ))

        // Sign the draft transaction.
        val stx = runFlow(bob, SignDraftTransactionByIDFlow(draftTxHash))

        // Bob can claim Alice's EVM committed asset
        val revertReceipt: TransactionReceipt = runFlow(bob,
            RevertCommitment(draftTxHash)
        )

        // bob collects signatures form oracles/validators of the block containing the claim's transfer event
        // asynchronously for the given transaction id
        runFlow(bob, CollectBlockSignaturesFlow(draftTxHash, revertReceipt.blockNumber, true))

        // Unlock and finalize the transfer to the recipient by producing and presenting proofs (that the EVM asset was
        // transferred to the expected recipient) to the lock contract verified during the new transaction.
        val utx = runFlow(bob,
            RevertAssetFlow(
                stx.tx.id,
                revertReceipt.blockNumber,
                Numeric.toBigInt(revertReceipt.transactionIndex!!)
            )
        )

        // Verify the unlocked asset ownership is now reverted to Bob
        Assert.assertEquals(
            bob.info.chooseIdentity().owningKey,
            (utx.tx.outputStates.single() as OwnableState).owner.owningKey
        )
        // Verify that alice can't see the locked asset anymore
        assert(alice.services.vaultService.queryBy(GenericAssetState::class.java, queryCriteria(assetName)).states.isEmpty())
    }

    @Test
    fun `bob can revert alice commit of the EVM asset`() {
        val amount = 1.toBigInteger()
        val transactionId = SecureHash.randomSHA256()
        val balanceBefore = alice.goldToken().balanceOf(aliceAddress).also {
            network.runNetwork()
        }.get()

        val commitTxReceipt: TransactionReceipt = runFlow(alice,
            CommitWithTokenFlow(transactionId, goldTokenDeployAddress, amount, bobAddress, amount, emptyList()
        ))

        val balanceAfterCommit = alice.goldToken().balanceOf(aliceAddress).also {
            network.runNetwork()
        }.get()

        val revertTxReceipt: TransactionReceipt = runFlow(bob,
            RevertCommitment(transactionId)
        )

        val balanceAfterRevert = alice.goldToken().balanceOf(aliceAddress).also {
            network.runNetwork()
        }.get()

        assertEquals(balanceBefore, balanceAfterRevert)
        assertEquals(balanceBefore, amount + balanceAfterCommit)
    }

    @Test
    fun `can generate matching hash`() {

        val assetName = UUID.randomUUID().toString()

        val assetTx : StateRef = runFlow(bob, IssueGenericAssetFlow(assetName))

        val commitTxReceipt: TransactionReceipt = runFlow(alice,
            CommitWithTokenFlow(assetTx.txhash, goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress)
        ))

        val commitmentHash1 = runFlow(alice, CommitmentHash(assetTx.txhash))

        val commitmentHash2 = commitmentHash(
            BigInteger.valueOf(1337),
            aliceAddress,
            bobAddress,
            amount,
            BigInteger.ZERO,
            goldTokenDeployAddress,
            BigInteger.ONE,
            listOf(charlieAddress)
        )

        val eq = Numeric.hexStringToByteArray(commitmentHash1)
        assertEquals(commitmentHash1, Numeric.toHexString(commitmentHash2.value))
    }

    @Test
    fun `alice can commit with token for bob`() {
        val commitTxReceipt: TransactionReceipt = runFlow(alice,
            CommitWithTokenFlow(SecureHash.randomSHA256(), goldTokenDeployAddress, amount, bobAddress, BigInteger.ONE, listOf(charlieAddress))
        )
    }

    private fun queryCriteria(assetName: String): QueryCriteria.VaultCustomQueryCriteria<GenericAssetSchemaV1.PersistentGenericAsset> {
        return builder {
            QueryCriteria.VaultCustomQueryCriteria(
                GenericAssetSchemaV1.PersistentGenericAsset::assetName.equal(
                    assetName
                )
            )
        }
    }

    private fun commitmentHash(
        chainId: BigInteger,
        owner: String,
        recipient: String,
        amount: BigInteger,
        tokenId: BigInteger,
        tokenAddress: String,
        signaturesThreshold: BigInteger,
        signers: List<String>
    ): Bytes32 {
        val parameters = listOf<org.web3j.abi.datatypes.Type<*>>(
            Uint256(chainId),
            Address(owner),
            Address(recipient),
            Uint256(amount),
            Uint256(tokenId),
            Address(tokenAddress),
            Uint256(signaturesThreshold),
            DynamicArray<Address>(Address::class.java, Utils.typeMap(signers, Address::class.java))
        )

        // Encode parameters using the DefaultFunctionEncoder
        val encodedParams = DefaultFunctionEncoder().encodeParameters(parameters)

        val bytes = Numeric.hexStringToByteArray(encodedParams)

        val hash = Hash.sha3(bytes)

        return Bytes32(hash)
    }
}
