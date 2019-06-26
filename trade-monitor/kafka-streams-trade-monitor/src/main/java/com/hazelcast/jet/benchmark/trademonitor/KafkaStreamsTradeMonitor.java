package com.hazelcast.jet.benchmark.trademonitor;

import com.hazelcast.jet.benchmark.trademonitor.RealTimeTradeProducer.MessageType;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Printed;
import org.apache.kafka.streams.kstream.Suppressed.BufferConfig;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import static com.hazelcast.jet.benchmark.trademonitor.RealTimeTradeProducer.MessageType.BYTE;
import static java.time.Duration.ofMillis;
import static org.apache.kafka.streams.kstream.Suppressed.untilWindowCloses;

public class KafkaStreamsTradeMonitor {

    public static void main(String[] args) {
        System.out.println("Arguments: " + Arrays.toString(args));
        if (args.length != 11) {
            System.err.println("Usage:");
            System.err.println("  " + KafkaStreamsTradeMonitor.class.getSimpleName() +
                    " <bootstrap.servers> <topic> <offset-reset> <maxLagMs> <windowSizeMs> <slideByMs>" +
                    " <snapshotIntervalMs> <outputPath> <kafkaParallelism>" +
                    " <sinkParallelism> <messageType>");
            System.err.println();
            System.err.println("<messageType> - byte|object");
            System.exit(1);
        }
        System.setProperty("hazelcast.logging.type", "log4j");
        Logger logger = Logger.getLogger(KafkaStreamsTradeMonitor.class);
        String brokerUri = args[0];
        String topic = args[1];
        String offsetReset = args[2];
        int lagMs = Integer.parseInt(args[3].replace("_", ""));
        int windowSize = Integer.parseInt(args[4].replace("_", ""));
        int slideBy = Integer.parseInt(args[5].replace("_", ""));
        int snapshotInterval = Integer.parseInt(args[6].replace("_", ""));
        String outputPath = args[7];
        int kafkaParallelism = Integer.parseInt(args[8]);
        int sinkParallelism = Integer.parseInt(args[9]);
        MessageType messageType = MessageType.valueOf(args[10].toUpperCase());
        logger.info(String.format("" +
                        "Starting Jet Trade Monitor with the following parameters:%n" +
                        "Kafka broker URI            %s%n" +
                        "Kafka topic                 %s%n" +
                        "Auto-reset message offset?  %s%n" +
                        "Allowed lag                 %s ms%n" +
                        "Sliding window size         %s ms%n" +
                        "Slide by                    %s ms%n" +
                        "Snapshot interval           %s ms%n" +
                        "Output path                 %s%n" +
                        "Source local parallelism    %s%n" +
                        "Sink local parallelism      %s%n" +
                        "Message type                %s%n",
                brokerUri, topic, offsetReset, lagMs, windowSize, slideBy, snapshotInterval,
                outputPath, kafkaParallelism, sinkParallelism, messageType
        ));

        Properties streamsProperties = getKafkaStreamsProperties(brokerUri, offsetReset, messageType);

        final StreamsBuilder builder = new StreamsBuilder();
        KStream<Long, Trade> stream = builder.stream(topic);
        stream.groupBy((key, value) -> value.getTicker(), Grouped.with(Serdes.String(), new TradeSerde()))
              .windowedBy(TimeWindows.of(ofMillis(windowSize))
                                     .advanceBy(ofMillis(slideBy))
                                     .grace(ofMillis(lagMs)))
              .count()
              .suppress(untilWindowCloses(BufferConfig.unbounded()))
              .mapValues((readOnlyKey, value) -> System.currentTimeMillis() - readOnlyKey.window().endTime().toEpochMilli() - lagMs)
              .filter((key, value) -> value > 0)
              .toStream()
              .print(Printed.toFile(outputPath));

        KafkaStreams streams = new KafkaStreams(builder.build(), streamsProperties);
        streams.cleanUp();
        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                streams.close();
            } catch (final Exception e) {
                // ignored
            }
        }));

    }

    private static Properties getKafkaStreamsProperties(String brokerUrl, String offsetReset, MessageType messageType) {
        Properties props = new Properties();
        props.setProperty(StreamsConfig.APPLICATION_ID_CONFIG, "kafka-streams-trade-monitor");
        props.setProperty(StreamsConfig.CLIENT_ID_CONFIG, "kafka-streams-trade-monitor-client");
        props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, brokerUrl);
        props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Long().getClass().getName());
        props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, TradeSerde.class.getName());
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        props.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class.getName());
        props.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                (messageType == BYTE ? ByteArrayDeserializer.class : TradeDeserializer.class).getName());
        props.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "32768");
        return props;
    }

    private static class Deserializer {
        final ResettableByteArrayInputStream is = new ResettableByteArrayInputStream();
        final DataInputStream ois = new DataInputStream(is);

        Trade deserialize(byte[] bytes) {
            is.setBuffer(bytes);
            try {
                String ticker = ois.readUTF();
                long time = ois.readLong();
                int price = ois.readInt();
                int quantity = ois.readInt();
                return new Trade(time, ticker, quantity, price);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class ResettableByteArrayInputStream extends ByteArrayInputStream {

        ResettableByteArrayInputStream() {
            super(new byte[0]);
        }

        void setBuffer(byte[] data) {
            super.buf = data;
            super.pos = 0;
            super.mark = 0;
            super.count = data.length;
        }
    }
}