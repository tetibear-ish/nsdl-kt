import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  type Edge,
  type EdgeProps,
  type Position,
} from "@xyflow/react";
import { LookingGlass } from "./LookingGlass";
import type { InspectionMode } from "./topology";

type InspectableEdgeData = { inspection?: InspectionMode };
type InspectableEdgeModel = Edge<InspectableEdgeData, "inspectable">;

export function InspectableEdge({
  sourceX,
  sourceY,
  sourcePosition,
  targetX,
  targetY,
  targetPosition,
  data,
  markerEnd,
  interactionWidth,
}: EdgeProps<InspectableEdgeModel>) {
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition: sourcePosition as Position,
    targetX,
    targetY,
    targetPosition: targetPosition as Position,
  });

  return (
    <>
      <BaseEdge path={path} markerEnd={markerEnd} interactionWidth={interactionWidth} />
      {data?.inspection && (
        <EdgeLabelRenderer>
          <div
            className={`edge-inspection-glass ${data.inspection}`}
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)` }}
          >
            <LookingGlass />
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
