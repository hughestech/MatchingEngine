package com.lykke.matching.engine.services.validators.input.impl

import com.lykke.matching.engine.daos.Asset
import com.lykke.matching.engine.daos.AssetPair
import com.lykke.matching.engine.daos.LimitOrder
import com.lykke.matching.engine.daos.Order
import com.lykke.matching.engine.daos.context.SingleLimitContext
import com.lykke.matching.engine.database.cache.ApplicationSettingsCache
import com.lykke.matching.engine.fee.checkFee
import com.lykke.matching.engine.holders.AssetsPairsHolder
import com.lykke.matching.engine.incoming.parsers.data.SingleLimitOrderParsedData
import com.lykke.matching.engine.order.OrderStatus
import com.lykke.matching.engine.services.validators.impl.OrderValidationException
import com.lykke.matching.engine.services.validators.input.LimitOrderInputValidator
import com.lykke.matching.engine.utils.NumberUtils
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class LimitOrderInputValidatorImpl(private val applicationSettingsCache: ApplicationSettingsCache,
                                   private val assetsPairHolder: AssetsPairsHolder) : LimitOrderInputValidator {
    override fun validateLimitOrder(singleLimitOrderParsedData: SingleLimitOrderParsedData) {
        val singleLimitContext = singleLimitOrderParsedData.messageWrapper.context as SingleLimitContext

        validateLimitOrder(singleLimitContext)
    }

    private fun validateLimitOrder(singleLimitContext: SingleLimitContext) {
        if (!singleLimitContext.isTrustedClient) {
            validateFee(singleLimitContext.limitOrder)
            validateAssets(singleLimitContext.assetPair)
        }

        validatePrice(singleLimitContext.limitOrder)
        validateVolume(singleLimitContext.limitOrder)
        validatePriceAccuracy(singleLimitContext.limitOrder, singleLimitContext.assetPair)
        validateVolumeAccuracy(singleLimitContext.limitOrder, singleLimitContext.baseAsset)
    }

    override fun validateLimitOrder(isTrustedClient: Boolean,
                                    order: LimitOrder,
                                    assetPair: AssetPair,
                                    baseAsset: Asset) {
        if (!isTrustedClient) {
            validateFee(order)
            validateAssets(assetPair)
        }

        validatePrice(order)
        validateVolume(order)
        validateValue(order)
        validatePriceAccuracy(order, assetPair)
        validateVolumeAccuracy(order, baseAsset)
    }

    override fun checkMinVolume(order: Order): Boolean {
        val assetPair = assetsPairHolder.getAssetPair(order.assetPairId)
        val volume = order.getAbsVolume()
        val minVolume = if (order.isStraight()) assetPair.minVolume else assetPair.minInvertedVolume
        return minVolume == null || volume >= minVolume
    }

    override fun validateStopOrder(singleLimitOrderParsedData: SingleLimitOrderParsedData) {
        val singleLimitContext = singleLimitOrderParsedData.messageWrapper.context as SingleLimitContext

        validateFee(singleLimitContext.limitOrder)
        validateAssets(singleLimitContext.assetPair)
        validateLimitPrices(singleLimitContext.limitOrder)
        validateVolume(singleLimitContext.limitOrder)
        validateVolumeAccuracy(singleLimitContext.limitOrder, singleLimitContext.baseAsset)
        validatePriceAccuracy(singleLimitContext.limitOrder, singleLimitContext.assetPair)
    }

    fun validateFee(order: LimitOrder) {
        if (order.fee != null && order.fees?.size ?: 0 > 1 || !checkFee(null, order.fees)) {
            throw OrderValidationException(OrderStatus.InvalidFee, "has invalid fee")
        }
    }

    fun validateAssets(assetPair: AssetPair) {
        if (applicationSettingsCache.isAssetDisabled(assetPair.baseAssetId) || applicationSettingsCache.isAssetDisabled(assetPair.quotingAssetId)) {
            throw OrderValidationException(OrderStatus.DisabledAsset, "disabled asset")
        }
    }

    fun validatePrice(limitOrder: LimitOrder) {
        if (limitOrder.price <= BigDecimal.ZERO) {
            throw OrderValidationException(OrderStatus.InvalidPrice, "price is invalid")
        }
    }

    fun validateLimitPrices(order: LimitOrder) {
        if ((order.lowerLimitPrice == null && order.lowerPrice == null && order.upperLimitPrice == null && order.upperPrice == null) ||
                ((order.lowerLimitPrice == null).xor(order.lowerPrice == null)) ||
                ((order.upperLimitPrice == null).xor(order.upperPrice == null)) ||
                (order.lowerLimitPrice != null && (order.lowerLimitPrice <= BigDecimal.ZERO || order.lowerPrice!! <= BigDecimal.ZERO)) ||
                (order.upperLimitPrice != null && (order.upperLimitPrice <= BigDecimal.ZERO || order.upperPrice!! <= BigDecimal.ZERO)) ||
                (order.lowerLimitPrice != null && order.upperLimitPrice != null && order.lowerLimitPrice >= order.upperLimitPrice)) {
            throw OrderValidationException(OrderStatus.InvalidPrice, "limit prices are invalid")
        }
    }

    fun validateValue(order: LimitOrder) {
        val assetPair = assetsPairHolder.getAssetPair(order.assetPairId)
        if (assetPair.maxValue != null && order.getAbsVolume() * order.price > assetPair.maxValue) {
            throw OrderValidationException(OrderStatus.InvalidVolume, "value is too large")
        }
    }

    fun validateVolume(limitOrder: LimitOrder) {
        val assetPair = assetsPairHolder.getAssetPair(limitOrder.assetPairId)
        if (!checkMinVolume(limitOrder)) {
            throw OrderValidationException(OrderStatus.TooSmallVolume, "volume is too small")
        }


        if (assetPair.maxVolume != null && limitOrder.getAbsVolume() > assetPair.maxVolume) {
            throw OrderValidationException(OrderStatus.InvalidVolume, "volume is too large")
        }
    }

    fun validateVolumeAccuracy(limitOrder: LimitOrder, baseAsset: Asset) {
        val baseAssetAccuracy = baseAsset.accuracy

        val volumeAccuracyValid = NumberUtils.isScaleSmallerOrEqual(limitOrder.volume, baseAssetAccuracy)
        if (!volumeAccuracyValid) {
            throw OrderValidationException(OrderStatus.InvalidVolumeAccuracy, "volume accuracy is invalid")
        }
    }

    fun validatePriceAccuracy(limitOrder: LimitOrder, assetPair: AssetPair) {
        val priceAccuracyValid = NumberUtils.isScaleSmallerOrEqual(limitOrder.price, assetPair.accuracy)
        if (!priceAccuracyValid) {
            throw OrderValidationException(OrderStatus.InvalidPriceAccuracy, "price accuracy is invalid")
        }
    }
}