package io.airbyte.workload.handler

import io.airbyte.db.instance.configs.jooq.generated.enums.WorkloadStatus
import io.airbyte.workload.api.domain.WorkloadLabel
import io.airbyte.workload.errors.InvalidStatusTransitionException
import io.airbyte.workload.errors.NotFoundException
import io.airbyte.workload.errors.NotModifiedException
import io.airbyte.workload.repository.WorkloadRepository
import io.airbyte.workload.repository.domain.Workload
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.OffsetDateTime
import java.util.Optional

class WorkloadHandlerTest {
  private val workloadRepository = mockk<WorkloadRepository>()
  private val workloadId = "test"
  private val dataplaneId = "dataplaneId"
  private val workloadHandler = WorkloadHandler(workloadRepository)

  @Test
  fun `test get workload`() {
    val domainWorkload =
      Workload(
        id = workloadId,
        dataplaneId = null,
        status = WorkloadStatus.pending,
        createdAt = null,
        updatedAt = null,
        lastHeartbeatAt = null,
        workloadLabels = null,
      )

    every { workloadRepository.findById(workloadId) }.returns(Optional.of(domainWorkload))
    val apiWorkload = workloadHandler.getWorkload(workloadId)
    assertEquals(domainWorkload.id, apiWorkload.id)
  }

  @Test
  fun `test workload not found`() {
    every { workloadRepository.findById(workloadId) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.getWorkload(workloadId) }
  }

  @Test
  fun `test create workload`() {
    val workloadLabel1 = WorkloadLabel("key1", "value1")
    val workloadLabel2 = WorkloadLabel("key2", "value2")
    val workloadLabels = mutableListOf(workloadLabel1, workloadLabel2)

    every { workloadRepository.existsById(workloadId) }.returns(false)
    every { workloadRepository.save(any()) }.returns(
      Workload(
        id = workloadId,
        dataplaneId = null,
        status = WorkloadStatus.pending,
        lastHeartbeatAt = null,
        workloadLabels = null,
      ),
    )
    workloadHandler.createWorkload(workloadId, workloadLabels)
    verify {
      workloadRepository.save(
        match {
          it.id == workloadId &&
            it.dataplaneId == null &&
            it.status == WorkloadStatus.pending &&
            it.lastHeartbeatAt == null &&
            it.workloadLabels!!.get(0).key == workloadLabel1.key &&
            it.workloadLabels!!.get(0).value == workloadLabel1.value &&
            it.workloadLabels!!.get(1).key == workloadLabel2.key &&
            it.workloadLabels!!.get(1).value == workloadLabel2.value
        },
      )
    }
  }

  @Test
  fun `test create workload id conflict`() {
    every { workloadRepository.existsById(workloadId) }.returns(true)
    assertThrows<NotModifiedException> { workloadHandler.createWorkload(workloadId, null) }
  }

  @Test
  fun `test get workloads`() {
    val domainWorkload =
      Workload(
        id = workloadId,
        dataplaneId = null,
        status = WorkloadStatus.pending,
        createdAt = null,
        updatedAt = null,
        lastHeartbeatAt = null,
        workloadLabels = null,
      )
    every { workloadRepository.search(any(), any(), any()) }.returns(listOf(domainWorkload))
    val workloads = workloadHandler.getWorkloads(listOf("dataplane1"), listOf(ApiWorkloadStatus.CLAIMED, ApiWorkloadStatus.FAILURE), null)
    assertEquals(1, workloads.size)
    assertEquals(workloadId, workloads.get(0).id)
  }

  @Test
  fun `test get workloads empty dataplane list`() {
    val workloads = workloadHandler.getWorkloads(emptyList(), listOf(ApiWorkloadStatus.CLAIMED), null)
    assertTrue(workloads.isEmpty())
  }

  @Test
  fun `test get workloads empty status list`() {
    val workloads = workloadHandler.getWorkloads(listOf("dataplane1"), emptyList(), null)
    assertTrue(workloads.isEmpty())
  }

  @Test
  fun `test update workload`() {
    every { workloadRepository.update(eq(workloadId), any()) }.returns(Unit)
    workloadHandler.updateWorkload(workloadId, ApiWorkloadStatus.CLAIMED)
    verify { workloadRepository.update(eq(workloadId), eq(WorkloadStatus.claimed)) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["claimed", "running"])
  fun `test successfulHeartbeat`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(workloadId) }.returns(
      Optional.of(
        Workload(
          id = workloadId,
          dataplaneId = null,
          status = workloadStatus,
          lastHeartbeatAt = null,
          workloadLabels = listOf(),
        ),
      ),
    )
    every { workloadRepository.update(eq(workloadId), any(), ofType(OffsetDateTime::class)) }.returns(Unit)
    workloadHandler.heartbeat(workloadId)
    verify { workloadRepository.update(eq(workloadId), eq(WorkloadStatus.running), any()) }
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["cancelled", "failure", "success", "pending"])
  fun `test nonAuthorizedHeartbeat`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(workloadId) }.returns(
      Optional.of(
        Workload(
          id = workloadId,
          dataplaneId = null,
          status = workloadStatus,
          lastHeartbeatAt = null,
          workloadLabels = listOf(),
        ),
      ),
    )
    assertThrows<InvalidStatusTransitionException> { workloadHandler.heartbeat(workloadId) }
  }

  @Test
  fun `test workload not found when claiming workload`() {
    every { workloadRepository.findById(workloadId) }.returns(Optional.empty())
    assertThrows<NotFoundException> { workloadHandler.claimWorkload(workloadId, dataplaneId) }
  }

  @Test
  fun `test workload has already been claimed`() {
    every { workloadRepository.findById(workloadId) }.returns(
      Optional.of(
        Workload(
          id = workloadId,
          dataplaneId = "otherDataplaneId",
          status = WorkloadStatus.running,
          lastHeartbeatAt = null,
          workloadLabels = listOf(),
        ),
      ),
    )
    assertFalse(workloadHandler.claimWorkload(workloadId, dataplaneId))
  }

  @ParameterizedTest
  @EnumSource(value = WorkloadStatus::class, names = ["claimed", "running", "success", "failure", "cancelled"])
  fun `test claim workload that is not pending`(workloadStatus: WorkloadStatus) {
    every { workloadRepository.findById(workloadId) }.returns(
      Optional.of(
        Workload(
          id = workloadId,
          dataplaneId = null,
          status = workloadStatus,
          lastHeartbeatAt = null,
          workloadLabels = listOf(),
        ),
      ),
    )
    assertThrows<InvalidStatusTransitionException> { workloadHandler.claimWorkload(workloadId, dataplaneId) }
  }

  @Test
  fun `test successful claim`() {
    every { workloadRepository.findById(workloadId) }.returns(
      Optional.of(
        Workload(
          id = workloadId,
          dataplaneId = null,
          status = WorkloadStatus.pending,
          lastHeartbeatAt = null,
          workloadLabels = listOf(),
        ),
      ),
    )

    every { workloadRepository.update(any(), any(), ofType(WorkloadStatus::class)) } just Runs

    assertTrue(workloadHandler.claimWorkload(workloadId, dataplaneId))
  }
}
