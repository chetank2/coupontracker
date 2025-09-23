"""Priority queue for managing samples that need annotation."""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, asdict
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any

LOGGER = logging.getLogger("priority_queue")


@dataclass
class PriorityItem:
    """An item in the priority queue."""
    image_path: str
    priority_score: float
    reason: str  # 'uncertainty', 'user_correction', 'parsing_failure', etc.
    metadata: Dict[str, Any]
    created_at: str
    processed: bool = False
    processed_at: Optional[str] = None


class PriorityQueue:
    """Manages priority queue for samples needing annotation."""
    
    def __init__(self, queue_path: Path = Path("data/feedback/priority_queue.json")):
        self.queue_path = queue_path
        self.queue_path.parent.mkdir(parents=True, exist_ok=True)
        self._load_queue()
    
    def _load_queue(self) -> None:
        """Load queue from disk."""
        if self.queue_path.exists():
            try:
                data = json.loads(self.queue_path.read_text())
                self.items = [PriorityItem(**item) for item in data.get("items", [])]
            except Exception as e:
                LOGGER.warning(f"Failed to load priority queue: {e}")
                self.items = []
        else:
            self.items = []
    
    def _save_queue(self) -> None:
        """Save queue to disk."""
        data = {
            "items": [asdict(item) for item in self.items],
            "last_updated": datetime.utcnow().isoformat() + "Z"
        }
        self.queue_path.write_text(json.dumps(data, indent=2))
    
    def add_item(
        self,
        image_path: str,
        priority_score: float,
        reason: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Add an item to the priority queue."""
        # Check if item already exists
        for item in self.items:
            if item.image_path == image_path and not item.processed:
                # Update existing item
                item.priority_score = max(item.priority_score, priority_score)
                item.reason = reason
                item.metadata.update(metadata or {})
                self._save_queue()
                return
        
        # Add new item
        item = PriorityItem(
            image_path=image_path,
            priority_score=priority_score,
            reason=reason,
            metadata=metadata or {},
            created_at=datetime.utcnow().isoformat() + "Z",
        )
        self.items.append(item)
        self._save_queue()
        
        LOGGER.info(f"Added item to priority queue: {image_path} (score: {priority_score:.3f})")
    
    def get_top_items(self, limit: int = 20) -> List[PriorityItem]:
        """Get top priority items that haven't been processed."""
        unprocessed = [item for item in self.items if not item.processed]
        unprocessed.sort(key=lambda x: x.priority_score, reverse=True)
        return unprocessed[:limit]
    
    def mark_processed(self, image_path: str) -> None:
        """Mark an item as processed."""
        for item in self.items:
            if item.image_path == image_path and not item.processed:
                item.processed = True
                item.processed_at = datetime.utcnow().isoformat() + "Z"
                self._save_queue()
                LOGGER.info(f"Marked item as processed: {image_path}")
                break
    
    def get_stats(self) -> Dict[str, Any]:
        """Get queue statistics."""
        total = len(self.items)
        processed = sum(1 for item in self.items if item.processed)
        unprocessed = total - processed
        
        # Group by reason
        reason_counts = {}
        for item in self.items:
            if not item.processed:
                reason_counts[item.reason] = reason_counts.get(item.reason, 0) + 1
        
        return {
            "total_items": total,
            "processed": processed,
            "unprocessed": unprocessed,
            "reason_breakdown": reason_counts,
        }
    
    def clear_processed(self) -> int:
        """Remove all processed items from the queue."""
        original_count = len(self.items)
        self.items = [item for item in self.items if not item.processed]
        removed_count = original_count - len(self.items)
        
        if removed_count > 0:
            self._save_queue()
            LOGGER.info(f"Cleared {removed_count} processed items from queue")
        
        return removed_count
