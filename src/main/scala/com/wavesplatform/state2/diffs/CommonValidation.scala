package com.wavesplatform.state2.diffs

import cats._
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state2.reader.StateReader
import com.wavesplatform.state2.{Portfolio, _}
import scorex.account.Address
import scorex.transaction.ValidationError.{GenericError, Mistiming}
import scorex.transaction._
import scorex.transaction.assets._
import scorex.transaction.assets.exchange.ExchangeTransaction
import vee.transaction.contract.{ChangeContractStatusTransaction, CreateContractTransaction}
import vee.transaction.database.DbPutTransaction
import scorex.transaction.lease.{LeaseCancelTransaction, LeaseTransaction}
import vee.transaction.MintingTransaction
import vee.transaction.spos.{ContendSlotsTransaction, ReleaseSlotsTransaction}

import scala.concurrent.duration._
import scala.util.{Left, Right}

object CommonValidation {

  val MaxTimeTransactionOverBlockDiff: FiniteDuration = 90.minutes
  val MaxTimePrevBlockOverTransactionDiff: FiniteDuration = 2.hours

  def disallowSendingGreaterThanBalance[T <: Transaction](s: StateReader, settings: FunctionalitySettings, blockTime: Long, tx: T): Either[ValidationError, T] =
    if (blockTime >= settings.allowTemporaryNegativeUntil)
      tx match {
        case ptx: PaymentTransaction if s.accountPortfolio(ptx.sender).balance < (ptx.amount + ptx.fee) =>
          Left(GenericError(s"Attempt to pay unavailable funds: balance " +
            s"${s.accountPortfolio(ptx.sender).balance} is less than ${ptx.amount + ptx.fee}"))
        case ttx: TransferTransaction =>
          val sender: Address = ttx.sender

          val amountDiff = ttx.assetId match {
            case Some(aid) => Portfolio(0, LeaseInfo.empty, Map(aid -> -ttx.amount))
            case None => Portfolio(-ttx.amount, LeaseInfo.empty, Map.empty)
          }
          val feeDiff = ttx.feeAssetId match {
            case Some(aid) => Portfolio(0, LeaseInfo.empty, Map(aid -> -ttx.fee))
            case None => Portfolio(-ttx.fee, LeaseInfo.empty, Map.empty)
          }

          val accountPortfolio = s.accountPortfolio(sender)
          val spendings = Monoid.combine(amountDiff, feeDiff)

          lazy val negativeAsset = spendings.assets.find { case (id, amt) => (accountPortfolio.assets.getOrElse(id, 0L) + amt) < 0L }.map { case (id, amt) => (id, accountPortfolio.assets.getOrElse(id, 0L), amt, accountPortfolio.assets.getOrElse(id, 0L) + amt) }
          lazy val newVEEBalance = accountPortfolio.balance + spendings.balance
          lazy val negativeVEE = newVEEBalance < 0
          if (negativeVEE)
            Left(GenericError(s"Attempt to transfer unavailable funds:" +
              s" Transaction application leads to negative vee balance to (at least) temporary negative state, current balance equals ${accountPortfolio.balance}, spends equals ${spendings.balance}, result is $newVEEBalance"))
          else if (negativeAsset.nonEmpty)
            Left(GenericError(s"Attempt to transfer unavailable funds:" +
              s" Transaction application leads to negative asset '${negativeAsset.get._1}' balance to (at least) temporary negative state, current balance is ${negativeAsset.get._2}, spends equals ${negativeAsset.get._3}, result is ${negativeAsset.get._4}"))
          else Right(tx)
        case _ => Right(tx)
      } else Right(tx)

  def disallowDuplicateIds[T <: Transaction](state: StateReader, settings: FunctionalitySettings, height: Int, tx: T): Either[ValidationError, T] = {
    if (state.containsTransaction(tx.id))
        Left(GenericError(s"Tx id cannot be duplicated. Current height is: $height. Tx with such id already present"))
    else Right(tx)
  }

  def disallowBeforeActivationTime[T <: Transaction](settings: FunctionalitySettings, tx: T): Either[ValidationError, T] =
    tx match {
      case tx: BurnTransaction if tx.timestamp <= settings.allowBurnTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowBurnTransactionAfter}"))
      case tx: LeaseTransaction if tx.timestamp <= settings.allowLeaseTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowLeaseTransactionAfter}"))
      case tx: LeaseCancelTransaction if tx.timestamp <= settings.allowLeaseTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowLeaseTransactionAfter}"))
      case tx: ExchangeTransaction if tx.timestamp <= settings.allowExchangeTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowExchangeTransactionAfter}"))
      case tx: CreateAliasTransaction if tx.timestamp <= settings.allowCreatealiasTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowCreatealiasTransactionAfter}"))
      case tx: ContendSlotsTransaction if tx.timestamp <= settings.allowContendSlotsTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowContendSlotsTransactionAfter}"))
      case tx: ReleaseSlotsTransaction if tx.timestamp <= settings.allowReleaseSlotsTransactionAfter =>
        Left(GenericError(s"must not appear before time=${settings.allowReleaseSlotsTransactionAfter}"))
      case _: BurnTransaction => Right(tx)
      case _: PaymentTransaction => Right(tx)
      case _: GenesisTransaction => Right(tx)
      case _: TransferTransaction => Right(tx)
      case _: IssueTransaction => Right(tx)
      case _: ReissueTransaction => Right(tx)
      case _: ExchangeTransaction => Right(tx)
      case _: LeaseTransaction => Right(tx)
      case _: LeaseCancelTransaction => Right(tx)
      case _: CreateAliasTransaction => Right(tx)
      case _: MintingTransaction => Right(tx)
      case _: ContendSlotsTransaction => Right(tx)
      case _: ReleaseSlotsTransaction => Right(tx)
      case _: CreateContractTransaction => Right(tx)
      case _: ChangeContractStatusTransaction => Right(tx)
      case _: DbPutTransaction => Right(tx)
      case _ => Left(GenericError("Unknown transaction must be explicitly registered within ActivatedValidator"))
    }

  def disallowTxFromFuture[T <: Transaction](settings: FunctionalitySettings, time: Long, tx: T): Either[ValidationError, T] = {
    val allowTransactionsFromFutureByTimestamp = tx.timestamp < settings.allowTransactionsFromFutureUntil

    if (!allowTransactionsFromFutureByTimestamp && (tx.timestamp - time) > MaxTimeTransactionOverBlockDiff.toNanos)
      Left(Mistiming(s"Transaction ts ${tx.timestamp} is from far future. BlockTime: $time"))
    else Right(tx)
  }

  def disallowTxFromPast[T <: Transaction](prevBlockTime: Option[Long], tx: T): Either[ValidationError, T] =
    prevBlockTime match {
      case Some(t) if (t - tx.timestamp) > MaxTimePrevBlockOverTransactionDiff.toNanos =>
        Left(Mistiming(s"Transaction ts ${tx.timestamp} is too old. Previous block time: $prevBlockTime"))
      case _ => Right(tx)
    }

  def disallowInvalidFeeScale[T <: Transaction](tx: T) : Either[ValidationError, T] = {
    if (tx.assetFee._3 != 100){
      Left(ValidationError.WrongFeeScale(tx.assetFee._3))
    } else{
      Right(tx)
    }
  }
}


