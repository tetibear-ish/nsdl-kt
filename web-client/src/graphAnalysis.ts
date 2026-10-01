export type GraphAnalysis = {
  nodeId: string;
  degree: number;
  connectedComponent: string[];
  shortestPaths: Record<string, number>;
  articulationPoint: boolean;
};
