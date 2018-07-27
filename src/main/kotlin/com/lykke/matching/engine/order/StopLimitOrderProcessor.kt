package com.lykke.matching.engine.order

import com.lykke.matching.engine.daos.LimitOrder
import com.lykke.matching.engine.daos.context.SingleLimitContext
import com.lykke.matching.engine.daos.WalletOperation
import com.lykke.matching.engine.database.cache.ApplicationSettingsCache
import com.lykke.matching.engine.database.common.entity.OrderBookPersistenceData
import com.lykke.matching.engine.database.common.entity.OrderBooksPersistenceData
import com.lykke.matching.engine.holders.AssetsHolder
import com.lykke.matching.engine.holders.AssetsPairsHolder
import com.lykke.matching.engine.holders.BalancesHolder
import com.lykke.matching.engine.holders.MessageSequenceNumberHolder
import com.lykke.matching.engine.messages.MessageStatus
import com.lykke.matching.engine.messages.MessageType
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.matching.engine.messages.ProtocolMessages
import com.lykke.matching.engine.outgoing.messages.LimitOrderWithTrades
import com.lykke.matching.engine.outgoing.messages.LimitOrdersReport
import com.lykke.matching.engine.outgoing.messages.v2.builders.EventFactory
import com.lykke.matching.engine.services.GenericLimitOrderService
import com.lykke.matching.engine.services.GenericStopLimitOrderService
import com.lykke.matching.engine.services.MessageSender
import com.lykke.matching.engine.services.validators.business.LimitOrderBusinessValidator
import com.lykke.matching.engine.services.validators.impl.OrderValidationException
import com.lykke.matching.engine.services.validators.impl.OrderValidationResult
import com.lykke.matching.engine.utils.NumberUtils
import com.lykke.matching.engine.utils.order.MessageStatusUtils
import org.apache.log4j.Logger
import java.math.BigDecimal
import java.util.Date
import java.util.UUID
import java.util.concurrent.BlockingQueue

class StopLimitOrderProcessor(private val limitOrderService: GenericLimitOrderService,
                              private val stopLimitOrderService: GenericStopLimitOrderService,
                              private val genericLimitOrderProcessor: GenericLimitOrderProcessor,
                              private val clientLimitOrdersQueue: BlockingQueue<LimitOrdersReport>,
                              private val assetsHolder: AssetsHolder,
                              private val assetsPairsHolder: AssetsPairsHolder,
                              private val balancesHolder: BalancesHolder,
                              private val limitOrderBusinessValidator: LimitOrderBusinessValidator,
                              private val messageSequenceNumberHolder: MessageSequenceNumberHolder,
                              private val messageSender: MessageSender,
                              private val LOGGER: Logger) {

    fun processStopOrder(messageWrapper: MessageWrapper, singleLimitContext: SingleLimitContext) {
        val order = singleLimitContext.limitOrder

        val assetPair = assetsPairsHolder.getAssetPair(order.assetPairId)
        val limitAsset = assetsHolder.getAsset(if (order.isBuySide()) assetPair.quotingAssetId else assetPair.baseAssetId)
        val limitVolume = if (order.isBuySide()) {
            val limitPrice = order.upperPrice ?: order.lowerPrice
            if (limitPrice != null)
                NumberUtils.setScaleRoundUp(order.volume * limitPrice, limitAsset.accuracy)
            else null
        } else order.getAbsVolume()

        val clientLimitOrdersReport = LimitOrdersReport(messageWrapper.messageId!!)
        var cancelVolume = BigDecimal.ZERO
        val ordersToCancel = mutableListOf<LimitOrder>()
        val newStopOrderBook = stopLimitOrderService.getOrderBook(order.assetPairId).getOrderBook(order.isBuySide()).toMutableList()
        if (singleLimitContext.isCancelOrders) {
            stopLimitOrderService.searchOrders(order.clientId, order.assetPairId, order.isBuySide()).forEach { orderToCancel ->
                ordersToCancel.add(orderToCancel)
                newStopOrderBook.remove(orderToCancel)
                clientLimitOrdersReport.orders.add(LimitOrderWithTrades(orderToCancel))
                cancelVolume += orderToCancel.reservedLimitVolume!!
            }
        }

        val availableBalance = NumberUtils.setScaleRoundHalfUp(balancesHolder.getAvailableBalance(limitOrder.clientId, limitAsset.assetId, cancelVolume), limitAsset.accuracy)

        val orderValidationResult = validateOrder(availableBalance, limitVolume, singleLimitContext)

        if (!orderValidationResult.isValid) {
            processInvalidOrder(messageWrapper, singleLimitContext,
                    orderValidationResult, balance, reservedBalance,
                    cancelVolume, ordersToCancel, clientLimitOrdersReport)
            return
        }

        val orderBook = limitOrderService.getOrderBook(limitOrder.assetPairId)
        val bestBidPrice = orderBook.getBidPrice()
        val bestAskPrice = orderBook.getAskPrice()

        var price: BigDecimal? = null
        if (limitOrder.lowerLimitPrice != null && (limitOrder.isBuySide() && bestAskPrice > BigDecimal.ZERO && bestAskPrice <= limitOrder.lowerLimitPrice ||
                !limitOrder.isBuySide() && bestBidPrice > BigDecimal.ZERO && bestBidPrice <= limitOrder.lowerLimitPrice)) {
            price = limitOrder.lowerPrice
        } else if (limitOrder.upperLimitPrice != null && (limitOrder.isBuySide() && bestAskPrice >= limitOrder.upperLimitPrice ||
                !limitOrder.isBuySide() && bestBidPrice >= limitOrder.upperLimitPrice)) {
            price = limitOrder.upperPrice
        }

        if (price != null) {
            LOGGER.info("Process stop order ${limitOrder.externalId}, client ${limitOrder.clientId} immediately (bestBidPrice=$bestBidPrice, bestAskPrice=$bestAskPrice)")
            limitOrder.updateStatus(OrderStatus.InOrderBook, singleLimitContext.orderProcessingStartTime)
            limitOrder.price = price

            genericLimitOrderProcessor.processLimitOrder(singleLimitContext, BigDecimal.ZERO)
            writeResponse(messageWrapper, order, MessageStatus.OK)
            return
        }

        val walletOperations = mutableListOf<WalletOperation>()
        walletOperations.add(WalletOperation(UUID.randomUUID().toString(),
                order.externalId,
                order.clientId,
                limitAsset.assetId, now, BigDecimal.ZERO, -cancelVolume))
        walletOperations.add(WalletOperation(UUID.randomUUID().toString(),
                order.externalId,
                order.clientId,
                limitAsset.assetId, now, BigDecimal.ZERO, limitVolume!!))
        val walletOperationsProcessor = balancesHolder.createWalletProcessor(LOGGER, true)
        walletOperationsProcessor.preProcess(walletOperations, true)

        order.reservedLimitVolume = limitVolume
        newStopOrderBook.add(order)
        val sequenceNumber = messageSequenceNumberHolder.getNewValue()
        messageWrapper.processedMessagePersisted = true
        val updated = walletOperationsProcessor.persistBalances(messageWrapper.processedMessage(),
                null,
                OrderBooksPersistenceData(listOf(OrderBookPersistenceData(order.assetPairId, order.isBuySide(), newStopOrderBook)),
                        listOf(order),
                        ordersToCancel),
                sequenceNumber)
        if (!updated) {
            writePersistenceErrorResponse(messageWrapper, limitOrder)
            return
        }

        walletOperationsProcessor.apply().sendNotification(order.externalId, MessageType.LIMIT_ORDER.name, messageWrapper.messageId!!)
        stopLimitOrderService.cancelStopLimitOrders(order.assetPairId, ordersToCancel, now)
        stopLimitOrderService.addStopOrder(order)

        clientLimitOrdersReport.orders.add(LimitOrderWithTrades(limitOrder))

        writeResponse(messageWrapper, limitOrder, MessageStatus.OK)
        LOGGER.info("${orderInfo(limitOrder)} added to stop order book")

        clientLimitOrdersQueue.put(clientLimitOrdersReport)

        val outgoingMessage = EventFactory.createExecutionEvent(sequenceNumber,
                messageWrapper.messageId!!,
                messageWrapper.id!!,
                singleLimitContext.orderProcessingStartTime,
                MessageType.LIMIT_ORDER,
                walletOperationsProcessor.getClientBalanceUpdates(),
                clientLimitOrdersReport.orders)
        messageSender.sendMessage(outgoingMessage)
    }

    private fun processInvalidOrder(messageWrapper: MessageWrapper, singleLimitContext: SingleLimitContext,
                                    orderValidationResult: OrderValidationResult,
                                    balance: BigDecimal, reservedBalance: BigDecimal,
                                    cancelVolume: BigDecimal,
                                    ordersToCancel: List<LimitOrder>, clientLimitOrdersReport: LimitOrdersReport) {
        LOGGER.info("${orderInfo(order)} ${e.message}")
        order.updateStatus(e.orderStatus, now)
        val messageStatus = MessageStatusUtils.toMessageStatus(e.orderStatus)
        val walletOperationsProcessor = balancesHolder.createWalletProcessor(LOGGER, true)
        if (cancelVolume > BigDecimal.ZERO) {
            walletOperationsProcessor.preProcess(listOf(WalletOperation(UUID.randomUUID().toString(),
                    order.externalId,
                    order.clientId,
                    limitAsset.assetId, now, BigDecimal.ZERO, -cancelVolume)), true)
        }
        val orderBooksPersistenceData = if (ordersToCancel.isNotEmpty())
            OrderBooksPersistenceData(listOf(OrderBookPersistenceData(order.assetPairId, order.isBuySide(), newStopOrderBook)),
                    emptyList(),
                    ordersToCancel) else null
        val sequenceNumber = messageSequenceNumberHolder.getNewValue()
        messageWrapper.processedMessagePersisted = true
        val updated = walletOperationsProcessor.persistBalances(messageWrapper.processedMessage(),
                null,
                orderBooksPersistenceData,
                sequenceNumber)
        if (updated) {
            walletOperationsProcessor.apply().sendNotification(order.externalId, MessageType.LIMIT_ORDER.name, messageWrapper.messageId!!)
            stopLimitOrderService.cancelStopLimitOrders(order.assetPairId, ordersToCancel, now)
            messageWrapper.writeNewResponse(ProtocolMessages.NewResponse.newBuilder()
                    .setId(order.externalId)
                    .setMatchingEngineId(order.id)
                    .setMessageId(messageWrapper.messageId)
                    .setStatus(messageStatus.type))

            clientLimitOrdersReport.orders.add(LimitOrderWithTrades(order))
            clientLimitOrdersQueue.put(clientLimitOrdersReport)

            val outgoingMessage = EventFactory.createExecutionEvent(sequenceNumber,
                    messageWrapper.messageId!!,
                    messageWrapper.id!!,
                    now,
                    MessageType.LIMIT_ORDER,
                    walletOperationsProcessor.getClientBalanceUpdates(),
                    clientLimitOrdersReport.orders)
            messageSender.sendMessage(outgoingMessage)
        } else {
            writePersistenceErrorResponse(messageWrapper, order)
        }
        return
    }

    private fun validateOrder(availableBalance: BigDecimal, limitVolume: BigDecimal, singleLimitContext: SingleLimitContext): OrderValidationResult {
        if (!singleLimitContext.validationResult!!.isValid) {
            return singleLimitContext.validationResult!!
        }

        try {
            limitOrderBusinessValidator.validateBalance(availableBalance, limitVolume)
        } catch (e: OrderValidationException) {
            return OrderValidationResult(false, e.message, e.orderStatus)
        }

        return OrderValidationResult(true)
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