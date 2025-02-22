package org.sk.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.sk.entity.ProducerMessageLog;
import org.sk.model.MessageWrapper;
import org.sk.repository.ProducerMessageLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

@Slf4j
public abstract class KafkaProducerTemplate {
    protected KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducerTemplate(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Autowired
    private ProducerMessageLogRepository producerMessageLogRepository;

    protected final ObjectMapper objectMapper = new ObjectMapper();
    protected final String JSON_VALUE = "JSON_VALUE";
    protected final String SOURCE = "SOURCE";
    protected final String SUCCESS = "SUCCESS";
    protected final String FAILED = "FAILED";
    protected final Function<MessageWrapper, BiFunction<SendResult<String, Object>, Throwable, Object>> updateLog = m -> (res, ex) -> {
        String result = SUCCESS;
        String errorMessage = null;
        if (res != null) {
            RecordMetadata rmd = res.getRecordMetadata();
            ProducerRecord<String, Object> pr = res.getProducerRecord();
            log.info("Topic: {}, Sent message: {}, Offset: {} ", rmd.topic(), pr.value(), rmd.offset());
        }
        if (ex != null) {
            result = FAILED;
            errorMessage = ex.getMessage();
            log.info("Unable to send message={} due to : {} ", m.getMessage(), ex.getMessage());
        }
        Message<String> msg = m.getMessage();
        ProducerMessageLog producerMessageLog = ProducerMessageLog.builder()
                .correlationId((String) msg.getHeaders().get(KafkaHeaders.CORRELATION_ID))
                .payload(msg.getPayload())
                .partitionId((Integer) msg.getHeaders().get(KafkaHeaders.RECEIVED_PARTITION))
                .topic((String) msg.getHeaders().get(KafkaHeaders.TOPIC))
                .errorMessage(errorMessage)
                .messagesSentTs(new Date())
                .processStatus(result)
                .build();
        producerMessageLogRepository.save(producerMessageLog);
        return result;
    };

    @Transactional
    public void publishMessage(Map<String, Object> input) {
        try {
            String jsonValue = objectMapper.writeValueAsString(input.get(SOURCE));
            input.put(JSON_VALUE, jsonValue);
            processJsonResult(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected abstract void processJsonResult(Map<String, Object> input);

    public abstract String getTopicType();

    protected Message<String> getMessage(String jsonValue, Integer partitionId) {
        return MessageBuilder.withPayload(jsonValue)
                .setHeader(KafkaHeaders.TOPIC, kafkaTemplate.getDefaultTopic())
                .setHeader(KafkaHeaders.CORRELATION_ID, UUID.randomUUID().toString())
                .setHeader(SOURCE, "Spring")
                .setHeader(KafkaHeaders.PARTITION, partitionId)
                .build();
    }

    protected Message<String> getMessageWithKey(String jsonValue, String key) {
        return MessageBuilder.withPayload(jsonValue)
                .setHeader(KafkaHeaders.TOPIC, kafkaTemplate.getDefaultTopic())
                .setHeader(KafkaHeaders.CORRELATION_ID, UUID.randomUUID().toString())
                .setHeader(SOURCE, "Spring")
                .setHeader(KafkaHeaders.KEY, key)
                .build();
    }

    protected void sendKafkaMsg(MessageWrapper result) {
        kafkaTemplate.send(result.getMessage()).whenComplete((success, throwable) -> updateLog.apply(result));
    }
}
