// The Atlas index tree.
//
// Roots the recursion: it loads the top level (regions) with `useAtlasNode('')`
// and renders one `AtlasNode` per region; every deeper level is that component
// disclosing itself. Loading and empty states use the shared `@ui` primitives the
// dashboard tabs already use, so the index reads as part of the same system.
import { EmptyState, Skeleton } from '@ui';
import { useAtlasNode } from '@/queries/atlas';
import { AtlasNode } from './AtlasNode';

const ROOT_PATH = '';

export function AtlasTree() {
  const root = useAtlasNode(ROOT_PATH);

  if (root.isPending) {
    return <Skeleton aria-label="Loading the index" />;
  }

  if (root.isError) {
    return (
      <EmptyState
        title="Couldn't load the index"
        body="Something went wrong reaching the atlas."
      />
    );
  }

  const regions = root.data.children;
  if (regions.length === 0) {
    return <EmptyState title="Nothing to index yet." />;
  }

  return (
    <div className="atlas-tree">
      {regions.map((node) => (
        <AtlasNode key={node.key} node={node} depth={0} />
      ))}
    </div>
  );
}
