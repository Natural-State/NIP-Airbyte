package io.airbyte.commons.temporal.queue

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.airbyte.commons.temporal.WorkflowClientWrapped
import io.airbyte.metrics.lib.MetricClient
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import io.temporal.workflow.Workflow
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import java.time.Duration

// Payload for the Queue
@JsonDeserialize(builder = TestQueueInput.Builder::class)
data class TestQueueInput(val input: String) {
  // Using a builder here to prove that we can use a payload with non-nullable fields.
  data class Builder(var input: String? = null) {
    fun input(input: String) = apply { this.input = input }

    fun build() = TestQueueInput(input = input!!)
  }
}

// The actual consumer
class TestConsumer : MessageConsumer<TestQueueInput> {
  override fun consume(input: TestQueueInput) {
    println(input)
  }
}

// Test implementation, this is required to map the activities and for registering an implementation with temporal.
class TestWorkflowImpl : QueueWorkflowBase<TestQueueInput>() {
  override lateinit var activity: QueueActivity<TestQueueInput>

  init {
    initializeActivity<QueueActivity<TestQueueInput>>()
  }

  // reified is a trick to be able to retrieve the type reference on a generic class to pass it to temporal.
  private inline fun <reified W : QueueActivity<TestQueueInput>> initializeActivity() {
    // Initializing the activity the official temporal way to be able to run the temporal standard tests.
    // For an actual workflow, we'd want to inject the activities so this init wouldn't be needed.
    this.activity =
      Workflow.newActivityStub(
        W::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build(),
      )
  }
}

class BasicQueueTest {
  companion object {
    val QUEUE_NAME = "testQueue"

    lateinit var activity: QueueActivityImpl<TestQueueInput>

    lateinit var testEnv: TestWorkflowEnvironment
    lateinit var worker: Worker
    lateinit var client: WorkflowClient

    @JvmStatic
    @BeforeAll
    fun setUp() {
      testEnv = TestWorkflowEnvironment.newInstance()
      worker = testEnv.newWorker(QUEUE_NAME)
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl::class.java)
      client = testEnv.workflowClient

      activity = spy(QueueActivityImpl(TestConsumer()))
      worker.registerActivitiesImplementations(activity)
      testEnv.start()
    }

    @JvmStatic
    @AfterAll
    fun tearDown() {
      testEnv.close()
    }
  }

  @Test
  fun testRoundTrip() {
    val producer = TemporalMessageProducer<TestQueueInput>(WorkflowClientWrapped(client, mock(MetricClient::class.java)))
    val message = TestQueueInput("boom!")
    producer.publish(QUEUE_NAME, message)

    verify(activity).consume(Message(message))
  }
}
