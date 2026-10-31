import XCTest
@testable import Audiobookshelf

final class DownloadReconcilerTests: XCTestCase {

    private func item(_ id: String, groupKey: String? = nil, parts: Set<String>, done: Bool = false, bytes: Double = 0) -> DownloadReconciler.ItemSummary {
        DownloadReconciler.ItemSummary(
            id: id,
            groupKey: groupKey ?? id,
            title: "Item \(id)",
            partIds: parts,
            isDone: done,
            bytesDownloaded: bytes
        )
    }

    func testEmptyStateProducesEmptyPlan() {
        let plan = DownloadReconciler.plan(items: [], livePartIds: [])
        XCTAssertTrue(plan.keptPartIds.isEmpty)
        XCTAssertTrue(plan.itemIdsToDelete.isEmpty)
        XCTAssertTrue(plan.messages.isEmpty)
    }

    func testLiveItemIsKept() {
        let plan = DownloadReconciler.plan(
            items: [item("a", parts: ["a1", "a2"])],
            livePartIds: ["a1"]
        )
        XCTAssertEqual(plan.keptPartIds, ["a1", "a2"])
        XCTAssertTrue(plan.itemIdsToDelete.isEmpty)
    }

    func testZombieItemWithoutLiveTasksIsDeleted() {
        let plan = DownloadReconciler.plan(
            items: [item("a", parts: ["a1"])],
            livePartIds: []
        )
        XCTAssertEqual(plan.itemIdsToDelete, ["a"])
        XCTAssertTrue(plan.keptPartIds.isEmpty)
        XCTAssertTrue(plan.messages.first?.contains("no active tasks") ?? false)
    }

    func testDuplicateItemsKeepTheOneWithMostProgress() {
        let plan = DownloadReconciler.plan(
            items: [
                item("a-old", groupKey: "a", parts: ["a1"], bytes: 100),
                item("a-new", groupKey: "a", parts: ["a2"], bytes: 500)
            ],
            livePartIds: ["a1", "a2"]
        )
        XCTAssertEqual(plan.keptPartIds, ["a2"])
        // The lower-progress duplicate is deleted, the furthest-along one survives
        XCTAssertEqual(plan.itemIdsToDelete, ["a-old"])
        XCTAssertTrue(plan.messages.contains { $0.contains("duplicate") })
    }

    func testCompletedItemsAreNeverDeleted() {
        let plan = DownloadReconciler.plan(
            items: [item("a", parts: ["a1"], done: true, bytes: 100)],
            livePartIds: []
        )
        XCTAssertTrue(plan.itemIdsToDelete.isEmpty)
        XCTAssertEqual(plan.keptPartIds, ["a1"])
    }

    func testDifferentItemsAreHandledIndependently() {
        let plan = DownloadReconciler.plan(
            items: [
                item("a", parts: ["a1"], bytes: 100),
                item("b", parts: ["b1"], bytes: 50)
            ],
            livePartIds: ["a1"]
        )
        XCTAssertEqual(plan.keptPartIds, ["a1"])
        XCTAssertEqual(plan.itemIdsToDelete, ["b"])
    }
}
