export interface AspiraLeaf {
  name: string;
  transactionLocationId: bigint;
  mapId: bigint;
  resourceLocationId: bigint | null;
  parentName: string | null;
}
