package bootcamp.token2;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.accounts.contracts.states.AccountInfo;
import com.r3.corda.lib.accounts.workflows.UtilitiesKt;
import com.r3.corda.lib.accounts.workflows.flows.RequestKeyForAccount;
import net.corda.core.flows.*;
import net.corda.core.identity.AnonymousParty;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import org.jetbrains.annotations.NotNull;
import java.util.Arrays;
import java.util.Collections;

public class Token2Flow {

    @InitiatingFlow
    @StartableByRPC
    public static class Token2IssuanceFlow extends FlowLogic<String> {

        private final String issuer;
        private final String owner;
        private final int amount;

        public Token2IssuanceFlow(String issuer, String owner, int amount) {
            this.issuer = issuer;
            this.owner = owner;
            this.amount = amount;
        }

        @Suspendable
        @Override
        public String call() throws FlowException {

            //Generate accountinfo & AnonymousParty object for transaction
            AccountInfo issuerAccountInfo = UtilitiesKt.getAccountService(this).accountInfo(issuer).get(0).getState().getData();
            AccountInfo ownerAccountInfo = UtilitiesKt.getAccountService(this).accountInfo(owner).get(0).getState().getData();

            AnonymousParty issuerAccount = subFlow(new RequestKeyForAccount(issuerAccountInfo));
            AnonymousParty ownerAccount = subFlow(new RequestKeyForAccount(ownerAccountInfo));

            FlowSession ownerSession = initiateFlow(ownerAccountInfo.getHost());

            //grab the notary for transaction building
            Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

            //create a transactionBuilder
            TransactionBuilder transactionBuilder = new TransactionBuilder(notary);
            Token2State token2State = new Token2State(issuerAccount, ownerAccount , amount);

            transactionBuilder.addOutputState(token2State);
            transactionBuilder.addCommand(new Token2Contract.Commands.Issue() ,
                    ImmutableList.of(issuerAccount.getOwningKey(), ownerAccount.getOwningKey()));

            transactionBuilder.verify(getServiceHub());

            //sign the transaction with the issuer account hosted on the Initiating node
            SignedTransaction selfSignedTransaction = getServiceHub().signInitialTransaction(transactionBuilder, issuerAccount.getOwningKey());


            //call CollectSignaturesFlow to get the signature from the owner by specifying with issuer key telling CollectSignaturesFlow that issuer has already signed the transaction
            final SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(selfSignedTransaction, Arrays.asList(ownerSession), Collections.singleton(issuerAccount.getOwningKey())));

            //call FinalityFlow for finality
            SignedTransaction stx = subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(ownerSession)));

            return "One Token2 State issued to "+owner+ " from " + issuer+ " with amount: "+amount +"\ntxId: "+ stx.getId() ;
        }
    }

    @InitiatedBy(Token2IssuanceFlow.class)
    public static class Token2IssuanceFlowResponder extends FlowLogic<Void> {

        private final FlowSession otherSide;

        public Token2IssuanceFlowResponder(FlowSession otherSide) {
            this.otherSide = otherSide;
        }

        @Override
        @Suspendable
        public Void call() throws FlowException {

            subFlow(new SignTransactionFlow(otherSide) {
                @Override
                protected void checkTransaction(@NotNull SignedTransaction stx) throws FlowException {
                    // Owner can add Custom Logic to validate transaction.
                }
            });
            subFlow(new ReceiveFinalityFlow(otherSide));

            return null;
        }
    }
}
