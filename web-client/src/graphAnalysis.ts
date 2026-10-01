export type GraphAnalysis = {
  degree: number;
  component: string[];
  shortestPaths: Record<string, string[]>;
  articulationPoint: boolean;
};

export function analyzeGraph(nodes: string[], edges: Array<[string, string]>, root: string): GraphAnalysis {
  const adjacency = new Map(nodes.map((id) => [id, new Set<string>()]));
  edges.forEach(([a, b]) => { adjacency.get(a)?.add(b); adjacency.get(b)?.add(a); });
  const paths: Record<string, string[]> = { [root]: [root] };
  const queue = [root];
  while (queue.length) {
    const current = queue.shift()!;
    for (const next of adjacency.get(current) ?? []) if (!paths[next]) {
      paths[next] = [...paths[current], next];
      queue.push(next);
    }
  }
  const component = Object.keys(paths).sort();
  const remaining = nodes.filter((id) => id !== root);
  const seen = new Set<string>();
  const pending = remaining.slice(0, 1);
  while (pending.length) {
    const current = pending.pop()!;
    if (seen.has(current)) continue;
    seen.add(current);
    for (const next of adjacency.get(current) ?? []) if (next !== root) pending.push(next);
  }
  return {
    degree: adjacency.get(root)?.size ?? 0,
    component,
    shortestPaths: paths,
    articulationPoint: component.length > 2 && remaining.some((id) => id in paths && !seen.has(id)),
  };
}
