import Foundation

// Decides which download items and tasks survive an app relaunch, independent
// of Realm and URLSession so the rules can be unit tested. Background
// URLSession tasks survive the app, and both zombie items (no live tasks) and
// duplicate items for the same library item have to be cleaned up.
struct DownloadReconciler {
    struct ItemSummary {
        var id: String
        var groupKey: String
        var title: String
        var partIds: Set<String>
        var isDone: Bool
        var bytesDownloaded: Double
    }

    struct Plan {
        var keptPartIds: Set<String> = []
        var itemIdsToDelete: [String] = []
        var messages: [String] = []
    }

    static func plan(items: [ItemSummary], livePartIds: Set<String>) -> Plan {
        var plan = Plan()
        let grouped = Dictionary(grouping: items) { $0.groupKey }

        for (_, items) in grouped {
            for item in items where item.isDone {
                plan.keptPartIds.formUnion(item.partIds)
            }

            let alive = items
                .filter { !$0.isDone && !$0.partIds.isDisjoint(with: livePartIds) }
                .sorted { $0.bytesDownloaded > $1.bytesDownloaded }

            for item in items where !item.isDone && !alive.contains(where: { $0.id == item.id }) {
                plan.itemIdsToDelete.append(item.id)
                plan.messages.append("Deleting stale download item for \(item.title) with no active tasks")
            }

            for (index, item) in alive.enumerated() {
                if index == 0 {
                    plan.keptPartIds.formUnion(item.partIds)
                } else {
                    plan.itemIdsToDelete.append(item.id)
                    plan.messages.append("Discarding duplicate download item for \(item.title)")
                }
            }
        }

        return plan
    }
}
