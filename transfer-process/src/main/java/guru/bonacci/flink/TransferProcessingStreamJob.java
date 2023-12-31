package guru.bonacci.flink;

import java.util.concurrent.TimeUnit;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.AsyncDataStream;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.async.AsyncRetryStrategy;
import org.apache.flink.streaming.util.retryable.AsyncRetryStrategies;
import org.apache.flink.streaming.util.retryable.RetryPredicates;
import org.apache.flink.util.OutputTag;

import guru.bonacci.flink.connector.Sinks;
import guru.bonacci.flink.connector.Sources;
import guru.bonacci.flink.domain.Transfer;
import guru.bonacci.flink.domain.TransferErrors;
import guru.bonacci.flink.domain.TransferRule;
import guru.bonacci.flink.gen.RuleGenerator;

public class TransferProcessingStreamJob {

	final static String TOPIC_TRANSFER_REQUESTS = "transfer_requests";
	final static String TOPIC_TRANSFERS = "transfers";
	final static String TOPIC_ERRORS = "houston";
	
	
	public static void main(String[] args) throws Exception {

		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(10);
		
		final OutputTag<Tuple2<Transfer, TransferErrors>> outputTagInvalidTransfer = 
				new OutputTag<Tuple2<Transfer, TransferErrors>>("invalid"){};

		DataStream<TransferRule> ruleStream = env.addSource(new RuleGenerator())
				.map(rule -> {
						System.out.println(rule);
						return rule;
					}
				);

		DataStream<Transfer> transferRequestStream = 
				env.fromSource(Sources.kafkaTransferConsumer(TOPIC_TRANSFER_REQUESTS, "flink"), 
												WatermarkStrategy.forMonotonousTimestamps(), "Kafka Source")
					 .filter(tf -> !tf.getFromId().equals(tf.getToId()));

		transferRequestStream.print();
		
		DataStream<Tuple2<Transfer, Boolean>> throttlingStream = transferRequestStream
			.keyBy(Transfer::getFromId)	
			.map(new RequestThrottler()).name("throttle");
		
		SingleOutputStreamOperator<Transfer> throttledStream = throttlingStream
			  .process(new Splitter(outputTagInvalidTransfer, TransferErrors.TOO_MANY_REQUESTS));

		throttledStream.getSideOutput(outputTagInvalidTransfer) // error handling
		 .map(tuple -> tuple.f1.toString() + ">" + tuple.f0.toString())
		 .sinkTo(Sinks.kafkaStringProducer(TOPIC_ERRORS));

		@SuppressWarnings("unchecked")
		AsyncRetryStrategy<Tuple2<Transfer, Double>> asyncRetryStrategy =
			new AsyncRetryStrategies.FixedDelayRetryStrategyBuilder<Tuple2<Transfer, Double>>(3, 100L) // maxAttempts=3, fixedDelay=100ms
				.ifResult(RetryPredicates.EMPTY_RESULT_PREDICATE)
				.ifException(RetryPredicates.HAS_EXCEPTION_PREDICATE)
				.build();
		
		 MapStateDescriptor<String, TransferRule> ruleStateDescriptor = 
		      new MapStateDescriptor<>(
		          "RulesBroadcastState",
		          BasicTypeInfo.STRING_TYPE_INFO,
		          TypeInformation.of(new TypeHint<TransferRule>() {}));
		 
		 BroadcastStream<TransferRule> ruleBroadcastStream = ruleStream.broadcast(ruleStateDescriptor);
		 
		 SingleOutputStreamOperator<Transfer> balancedStream = 
				AsyncDataStream.orderedWaitWithRetry(throttledStream, 
																							new PinotSufficientFunds(), 
																							1000, TimeUnit.MILLISECONDS, 100, asyncRetryStrategy)
				.name("sufficient-funds")
				.connect(ruleBroadcastStream)
				.process(new DynamicBalanceSplitter(outputTagInvalidTransfer));

		 balancedStream.getSideOutput(outputTagInvalidTransfer) // error handling
			 .map(tuple -> tuple.f1.toString() + ">" + tuple.f0.toString())
			 .sinkTo(Sinks.kafkaStringProducer(TOPIC_ERRORS));

		 DataStream<Tuple2<Transfer, String>> lastRequestStream = 
				 balancedStream
					.keyBy(Transfer::getFromId)	
					.process(new LastRequestCache()).name("request-cache");

		 SingleOutputStreamOperator<Transfer> dbInSyncStream = 
				 AsyncDataStream.orderedWait(lastRequestStream, 
						 													new PinotLastRequestProcessed(), 
						 													1000, TimeUnit.MILLISECONDS, 1000)
					.name("db-in-sync")
				  .process(new Splitter(outputTagInvalidTransfer, TransferErrors.TRANSFER_IN_PROGRESS));

		 dbInSyncStream.getSideOutput(outputTagInvalidTransfer) // error handling
			 .map(tuple -> tuple.f1.toString() + ">" + tuple.f0.toString())
			 .sinkTo(Sinks.kafkaStringProducer(TOPIC_ERRORS));

		 balancedStream.map(new MapFunction<Transfer, Tuple2<Transfer, String>>() {

			@Override
			public Tuple2<Transfer, String> map(Transfer tr) {
				return Tuple2.<Transfer, String>of(tr, tr.getFromId());			}
		 	})
		 	.sinkTo(Sinks.kafkaTransferProducer(TOPIC_TRANSFERS));
		 
		env.execute("transfer-request processing");
	}
}
