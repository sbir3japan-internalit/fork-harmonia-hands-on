package com.interop.flows

import co.paralleluniverse.fibers.Suspendable
import com.interop.flows.BlockSignaturesCollectorFlow.CollectBlockSignaturesFlow
import com.interop.flows.BlockSignaturesCollectorFlow.CollectorInitiator
import com.r3.corda.evminterop.services.evmInterop
import com.r3.corda.evminterop.services.swap.DraftTxService
import com.r3.corda.evminterop.states.swap.LockState
import net.corda.core.crypto.*
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.unwrap
import java.math.BigInteger
import java.security.PublicKey

typealias CollectNotarizationSignaturesFlow = NotarizationSignaturesCollectorFlow.CollectNotarizationSignaturesFlow

object NotarizationSignaturesCollectorFlow {

    @CordaSerializable
    data class RequestParams(
        val transactionId: SecureHash,
        val transactionSignature: TransactionSignature,
        val notary: PublicKey,
        val blocking: Boolean
    )

    @CordaSerializable
    data class StoreParams(
        val transactionId: SecureHash,
        val signature: ByteArray,
        val notary: PublicKey,
        val blocking: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as StoreParams

            if (transactionId != other.transactionId) return false
            if (!signature.contentEquals(other.signature)) return false
            if (notary != other.notary) return false
            if (blocking != other.blocking) return false

            return true
        }

        override fun hashCode(): Int {
            var result = transactionId.hashCode()
            result = 31 * result + signature.contentHashCode()
            result = 31 * result + notary.hashCode()
            result = 31 * result + blocking.hashCode()
            return result
        }
    }

    /**
     * [CollectNotarizationSignaturesFlow] initiates the signatures collection from the lock-state approved validators
     * asynchronously, blocking or non-blocking. This flow initiates a responder flow on each approved validator so
     * that they can all verify the given signature as soon as they receive the message and asynchronously report the
     * signature and store it on the initiator node (this) through a secondary flow [CollectorInitiator] that stores
     * the incoming signatures.
     *
     * @param transactionId the transaction hash of the signed draft transaction to unlock
     * @param blocking indicates whether the initiating flow will wait for the responder flow to complete
     */
    @Suspendable
    @StartableByRPC
    @InitiatingFlow
    class CollectNotarizationSignaturesFlow(
        val transactionId: SecureHash,
        val blocking: Boolean
    ) : FlowLogic<Unit>() {

        companion object {
            val log = loggerFor<CollectNotarizationSignaturesFlow>()
        }

        @Suspendable
        override fun call() {
            val signedTransaction = serviceHub.validatedTransactions.getTransaction(transactionId)
                ?: throw IllegalArgumentException("Transaction not found for ID: $transactionId")

            val lockState = signedTransaction.tx.outputs
                .mapNotNull { it.data as? LockState }
                .singleOrNull()
                ?: throw IllegalArgumentException("Transaction $transactionId does not have a lock state")

            val validators = lockState.approvedValidators.mapNotNull {
                serviceHub.identityService.partyFromKey(it)
            }

            val sessions = validators.map { initiateFlow(it) }

            val notary = signedTransaction.tx.notary!!.owningKey

            val signature = signedTransaction.sigs.singleOrNull {
                it.by == notary
            } ?: throw IllegalArgumentException("Transaction $transactionId is not signed by the expected notary")

            val receivableSessions = mutableListOf<FlowSession>()
            for (session in sessions) {
                try {
                    session.send(
                        RequestParams(
                            transactionId,
                            signature,
                            notary,
                            blocking
                        )
                    )
                    receivableSessions.add(session)
                } catch (e: Throwable) {
                    log.error("Error while sending request.\nError: $e")
                }
            }

            if (blocking) {
                for (session in receivableSessions) {
                    try {
                        session.receive<Boolean>()
                    } catch (e: Throwable) {
                        log.error("Error while receiving response.\nError: $e")
                    }
                }
            }
        }
    }

    @Suspendable
    @StartableByRPC
    @InitiatedBy(CollectNotarizationSignaturesFlow::class)
    class CollectNotarizationSignaturesFlowResponder(private val session: FlowSession) : FlowLogic<Unit>() {

        companion object {
            val log = loggerFor<CollectNotarizationSignaturesFlowResponder>()
        }

        @Suspendable
        override fun call() {
            val request = session.receive<RequestParams>().unwrap { it }

            subFlow(CollectorInitiator(session.counterparty, request))

            if (request.blocking) {
                // send a dummy response to unblock the initiating flow
                try {
                    session.send(true)
                } catch (e: Throwable) {
                    log.error("Error while sending response.\nError: $e")
                }
            }
        }
    }

    /**
     * [CollectorInitiator] verify the signature belongs to the given notary and it is over the transaction id.
     * If positive, signs the transaction id and notary identity with the node's EVM identity.
     *
     * @param recipient the node that will receive the signature.
     * @param requestParams request params including transaction id, notary signature, notary public key, blocking mode.
     */
    @Suspendable
    @StartableByRPC
    @InitiatingFlow
    class CollectorInitiator(
        private val recipient: AbstractParty,
        private val requestParams: RequestParams
    ) : FlowLogic<Unit>() {

        companion object {
            val log = loggerFor<CollectorInitiator>()
        }

        @Suspendable
        override fun call() {
            if (requestParams.transactionSignature.isValid(requestParams.transactionId)) {
                // Notary signature validates for the transaction ID, therefore this validator signs
                // with its EVM signature that will need to recover to an EVM validator address
                val signature = serviceHub.evmInterop().web3Provider().signData(requestParams.transactionId.bytes)

                val session = initiateFlow(recipient)

                session.send(StoreParams(
                    requestParams.transactionId,
                    signature,
                    requestParams.notary,
                    requestParams.blocking
                ))

                if (requestParams.blocking) {
                    // wait for a dummy response before returning to the caller
                    try {
                        session.receive<Boolean>()
                    } catch (e: Throwable) {
                        log.error("Error while receiving response.\nError: $e")
                    }
                }
            }
        }
    }

    @Suspendable
    @StartableByRPC
    @InitiatedBy(CollectorInitiator::class)
    class CollectorResponder(private val session: FlowSession) : FlowLogic<Unit?>() {

        companion object {
            val log = loggerFor<CollectorResponder>()
        }

        @Suspendable
        override fun call() {
            val params = try {
                session.receive<StoreParams>().unwrap { it }
            } catch (e: Throwable) {
                log.error("Error while receiving response.\nError: $e")
                throw e
            }

            serviceHub.cordaService(DraftTxService::class.java).saveNotarizationProof(
                params.transactionId,
                params.signature
            )

            if (params.blocking) {
                // send a dummy response to unblock the initiating flow
                try {
                    session.send(true)
                } catch (e: Throwable) {
                    log.error("Error while sending response.\nError: $e")
                }
            }
        }
    }

    /**
     * Helper flow to query the block signatures store for a given block number
     */
    @Suspendable
    @StartableByRPC
    @InitiatingFlow
    class S(
        private val blockNumber: BigInteger
    ) : FlowLogic<String>() {
        @Suspendable
        override fun call(): String {
            val signatures: List<DigitalSignature.WithKey> =
                serviceHub.cordaService(DraftTxService::class.java).blockSignatures(blockNumber)
            return signatures.map {
                it.by.toString()
            }.joinToString { ", " }
        }
    }
}
