package com.lykke.matching.engine.outgoing.rabbit.impl

import com.lykke.matching.engine.logging.DatabaseLogger
import com.lykke.matching.engine.logging.LogMessageTransformer
import com.lykke.matching.engine.outgoing.messages.v2.events.Event
import com.lykke.matching.engine.outgoing.rabbit.RabbitMqService
import com.lykke.matching.engine.utils.config.RabbitConfig
import com.rabbitmq.client.BuiltinExchangeType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
import java.util.concurrent.BlockingQueue

@Service("rabbitMqService")
@Profile("default")
class RabbitMqServiceImpl(private val jsonMessageTransformer: LogMessageTransformer) : RabbitMqService<Event<*>> {
    override fun startPublisher(config: RabbitConfig, queue: BlockingQueue<out Event<*>>,
                                appName: String, appVersion: String, exchangeType: BuiltinExchangeType,
                                messageDatabaseLogger: DatabaseLogger?) {
        RabbitMqPublisher(config.uri, config.exchange, queue, appName, appVersion, exchangeType,
               jsonMessageTransformer, messageDatabaseLogger).start()
    }
}