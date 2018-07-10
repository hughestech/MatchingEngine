
package com.lykke.matching.engine.order

import com.lykke.matching.engine.daos.AssetPair
import com.lykke.matching.engine.daos.LimitOrder
import com.lykke.matching.engine.daos.context.SingleLimitContext
import com.lykke.matching.engine.database.cache.ApplicationSettingsCache
import com.lykke.matching.engine.holders.AssetsHolder
import com.lykke.matching.engine.holders.AssetsPairsHolder
import com.lykke.matching.engine.holders.BalancesHolder
import com.lykke.matching.engine.holders.MessageSequenceNumberHolder
import com.lykke.matching.engine.messages.MessageStatus
import com.lykke.matching.engine.messages.MessageType
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.matching.engine.messages.ProtocolMessages
import com.lykke.matching.engine.outgoing.messages.BalanceUpdate
import com.lykke.matching.engine.outgoing.messages.ClientBalanceUpdate
import com.lykke.matching.engine.outgoing.messages.JsonSerializable
import com.lykke.matching.engine.outgoing.messages.LimitOrderWithTrades
import com.lykke.matching.engine.outgoing.messages.LimitOrdersReport
import com.lykke.matching.engine.outgoing.messages.v2.builders.EventFactory
import com.lykke.matching.engine.services.GenericLimitOrderService
import com.lykke.matching.engine.services.GenericStopLimitOrderService
import com.lykke.matching.engine.services.MessageSender
import com.lykke.matching.engine.utils.NumberUtils
import com.lykke.matching.engine.utils.order.MessageStatusUtils
import org.apache.log4j.Logger
import java.math.BigDecimal
import java.util.Date
import java.util.concurrent.BlockingQueue

class StopLimitOrderProcessor(private val limitOrderService: GenericLimitOrderService,
                              private val stopLimitOrderService: GenericStopLimitOrderService,
                              private val genericLimitOrderProcessor: GenericLimitOrderProcessor,
                              private val clientLimitOrderReportQueue: BlockingQueue<JsonSerializable>,
                              private val assetsHolder: AssetsHolder,
                              private val assetsPairsHolder: AssetsPairsHolder,
                              private val balancesHolder: BalancesHolder,
                              applicationSettingsCache: ApplicationSettingsCache,
                              private val messageSequenceNumberHolder: MessageSequenceNumberHolder,
                              private val messageSender: MessageSender,
                              private val LOGGER: Logger) {

    private val validator = LimitOrderValidator(assetsPairsHolder, assetsHolder, applicationSettingsCache)

    fun processStopOrder(messageWrapper: MessageWrapper, singleLimitContext: SingleLimitContext) {
        val limitOrder = singleLimitContext.limitOrder

        val assetPair = assetsPairsHolder.getAssetPair(limitOrder.assetPairId)
        val limitAsset = assetsHolder.getAsset(if (limitOrder.isBuySide()) assetPair.quotingAssetId else assetPair.baseAssetId)
        val limitVolume = if (limitOrder.isBuySide())
            NumberUtils.setScaleRoundUp(limitOrder.volume * (limitOrder.upperPrice ?: limitOrder.lowerPrice)!!, limitAsset.accuracy)
        else
            limitOrder.getAbsVolume()

        val balance = balancesHolder.getBalance(limitOrder.clientId, limitAsset.assetId)
        val reservedBalance = balancesHolder.getReservedBalance(limitOrder.clientId, limitAsset.assetId)
        val clientLimitOrdersReport = LimitOrdersReport(messageWrapper.messageId!!)
        var cancelVolume = BigDecimal.ZERO
        val ordersToCancel = mutableListOf<LimitOrder>()
        if (singleLimitContext.cancelOrders) {
            stopLimitOrderService.searchOrders(limitOrder.clientId, limitOrder.assetPairId, limitOrder.isBuySide()).forEach { orderToCancel ->
                ordersToCancel.add(orderToCancel)
                clientLimitOrdersReport.orders.add(LimitOrderWithTrades(orderToCancel))
                cancelVolume += orderToCancel.reservedLimitVolume!!
            }
        }

        val availableBalance = NumberUtils.setScaleRoundHalfUp(balancesHolder.getAvailableBalance(limitOrder.clientId, limitAsset.assetId, cancelVolume), limitAsset.accuracy)
        try {
            validateOrder(limitOrder, assetPair, availableBalance, limitVolume)
        } catch (e: OrderValidationException) {
            LOGGER.info("${orderInfo(limitOrder)} ${e.message}")
            limitOrder.updateStatus(e.orderStatus, singleLimitContext.orderProcessingStartTime)
            val messageStatus = MessageStatusUtils.toMessageStatus(e.orderStatus)
            var updated = true
            val clientBalanceUpdates = mutableListOf<ClientBalanceUpdate>()

            val sequenceNumber = messageSequenceNumberHolder.getNewValue()
            if (cancelVolume > BigDecimal.ZERO) {
                val newReservedBalance = NumberUtils.setScaleRoundHalfUp(reservedBalance - cancelVolume, limitAsset.accuracy)
                updated = balancesHolder.updateReservedBalance(singleLimitContext.processedMessage,
                        sequenceNumber,
                        limitOrder.clientId,
                        limitAsset.assetId,
                        newReservedBalance)
                if (updated) {
                    clientBalanceUpdates.add(ClientBalanceUpdate(limitOrder.clientId, limitAsset.assetId, balance, balance, reservedBalance, newReservedBalance))
                    balancesHolder.sendBalanceUpdate(BalanceUpdate(limitOrder.externalId, MessageType.LIMIT_ORDER.name, Date(),
                            clientBalanceUpdates,
                            messageWrapper.messageId!!))
                }
            }

            if (updated) {
                stopLimitOrderService.cancelStopLimitOrders(limitOrder.assetPairId, limitOrder.isBuySide(), ordersToCancel, singleLimitContext.orderProcessingStartTime)
                messageWrapper.writeNewResponse(ProtocolMessages.NewResponse.newBuilder()
                        .setId(limitOrder.externalId)
                        .setMatchingEngineId(limitOrder.id)
                        .setMessageId(messageWrapper.messageId)
                        .setStatus(messageStatus.type))

                clientLimitOrdersReport.orders.add(LimitOrderWithTrades(limitOrder))
                clientLimitOrderReportQueue.put(clientLimitOrdersReport)

                val outgoingMessage = EventFactory.createExecutionEvent(sequenceNumber,
                        messageWrapper.messageId!!,
                        messageWrapper.id!!,
                        singleLimitContext.orderProcessingStartTime,
                        MessageType.LIMIT_ORDER,
                        clientBalanceUpdates,
                        clientLimitOrdersReport.orders)
                messageSender.sendMessage(outgoingMessage)
            } else {
                writePersistenceErrorResponse(messageWrapper, limitOrder)
            }
            return
        }

        val orderBook = limitOrderService.getOrderBook(limitOrder.assetPairId)
        val bestBidPrice = orderBook.getBidPrice()
        val bestAskPrice = orderBook.getAskPrice()

        var price: BigDecimal? = null
        if (limitOrder.lowerLimitPrice != null && (limitOrder.isBuySide() && bestAskPrice > BigDecimal.ZERO && bestAskPrice <= limitOrder.lowerLimitPrice ||
                !limitOrder.isBuySide() && bestBidPrice > BigDecimal.ZERO && bestBidPrice <= limitOrder.lowerLimitPrice)) {
            price = limitOrder.lowerPrice
        } else if(limitOrder.upperLimitPrice != null && (limitOrder.isBuySide() && bestAskPrice >= limitOrder.upperLimitPrice ||
                !limitOrder.isBuySide() && bestBidPrice >= limitOrder.upperLimitPrice)) {
            price = limitOrder.upperPrice
        }

        if (price != null) {
            LOGGER.info("Process stop order ${limitOrder.externalId}, client ${limitOrder.clientId} immediately (bestBidPrice=$bestBidPrice, bestAskPrice=$bestAskPrice)")
            limitOrder.updateStatus(OrderStatus.InOrderBook, singleLimitContext.orderProcessingStartTime)
            limitOrder.price = price

            genericLimitOrderProcessor.processLimitOrder(messageWrapper.messageId!!,
                    singleLimitContext.processedMessage,
                    limitOrder,
                    singleLimitContext.orderProcessingStartTime,
                    BigDecimal.ZERO)
            return
        }

        val newReservedBalance = NumberUtils.setScaleRoundHalfUp(reservedBalance - cancelVolume + limitVolume, limitAsset.accuracy)

        val clientBalanceUpdates = listOf(ClientBalanceUpdate(limitOrder.clientId,
                limitAsset.assetId,
                balance,
                balance,
                reservedBalance,
                newReservedBalance))

        val sequenceNumber = messageSequenceNumberHolder.getNewValue()

        val updated = balancesHolder.updateReservedBalance(singleLimitContext.processedMessage,
                sequenceNumber,
                limitOrder.clientId,
                limitAsset.assetId,
                newReservedBalance)

        if (!updated) {
            writePersistenceErrorResponse(messageWrapper, limitOrder)
            return
        }

        balancesHolder.sendBalanceUpdate(BalanceUpdate(limitOrder.externalId,
                MessageType.LIMIT_ORDER.name,
                singleLimitContext.orderProcessingStartTime,
                clientBalanceUpdates,
                messageWrapper.messageId!!))
        stopLimitOrderService.cancelStopLimitOrders(limitOrder.assetPairId, limitOrder.isBuySide(), ordersToCancel, singleLimitContext.orderProcessingStartTime)

        limitOrder.reservedLimitVolume = limitVolume
        stopLimitOrderService.addStopOrder(limitOrder)

        clientLimitOrdersReport.orders.add(LimitOrderWithTrades(limitOrder))

        writeResponse(messageWrapper, limitOrder, MessageStatus.OK)
        LOGGER.info("${orderInfo(limitOrder)} added to stop order book")

        clientLimitOrderReportQueue.put(clientLimitOrdersReport)

        val outgoingMessage = EventFactory.createExecutionEvent(sequenceNumber,
                messageWrapper.messageId!!,
                messageWrapper.id!!,
                singleLimitContext.orderProcessingStartTime,
                MessageType.LIMIT_ORDER,
                clientBalanceUpdates,
                clientLimitOrdersReport.orders)
        messageSender.sendMessage(outgoingMessage)
    }

    private fun validateOrder(order: LimitOrder, assetPair: AssetPair, availableBalance: BigDecimal, limitVolume: BigDecimal) {
        validator.validateFee(order)
        validator.validateAssets(assetPair)
        validator.validateLimitPrices(order)
        validator.validateVolume(order)
        validator.checkBalance(availableBalance, limitVolume)
        validator.validateVolumeAccuracy(order)
        validator.validatePriceAccuracy(order)
    }

    private fun orderInfo(order: LimitOrder): String {
        return "Stop limit order (id: ${order.externalId})"
    }

    private fun writeResponse(messageWrapper: MessageWrapper, order: LimitOrder, status: MessageStatus, reason: String? = null) {
        val builder = ProtocolMessages.NewResponse.newBuilder()
                .setMatchingEngineId(order.id)
                .setStatus(status.type)
        if (reason != null) {
            builder.statusReason = reason
        }
        messageWrapper.writeNewResponse(builder)
    }

    private fun writePersistenceErrorResponse(messageWrapper: MessageWrapper, order: LimitOrder) {
        val message = "Unable to save result data"
        LOGGER.error("$message (stop limit order id ${order.externalId})")
        messageWrapper.writeNewResponse(ProtocolMessages.NewResponse.newBuilder()
                .setMatchingEngineId(order.id)
                .setStatus(MessageStatusUtils.toMessageStatus(order.status).type)
                .setStatusReason(message))
    }
}