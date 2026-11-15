import storageServiceProxy from "../proxies/StorageServiceProxy";

export interface StoredPose {
  name: string;
  poseData: Blob;
  metadata: { originalFileName: string; uploadedAt: number; fileSize: number };
}

export interface PoseListItem {
  id: string;
  name: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

class PoseStorageService {
  async savePose(file: File): Promise<string> {
    if (!file.name.toLowerCase().endsWith(".vpd") || !file.size) {
      throw new Error("Select a non-empty VPD pose file.");
    }
    const { VpdReader } =
      await import("babylon-mmd/esm/Loader/Parser/vpdReader");
    const text = new TextDecoder("shift_jis").decode(await file.arrayBuffer());
    const parsed = VpdReader.Parse(text);
    const bones = Object.values(parsed.bones);
    const morphs = Object.values(parsed.morphs);
    if (
      (bones.length === 0 && morphs.length === 0) ||
      bones.some((bone) =>
        [...bone.rotation, ...(bone.position ?? [])].some(
          (value) => !Number.isFinite(value),
        ),
      ) ||
      morphs.some((value) => !Number.isFinite(value))
    ) {
      throw new Error("The VPD file does not contain a valid pose.");
    }
    const id = `pose_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
    const name = file.name.replace(/\.vpd$/i, "").trim() || "Pose";
    const pose: StoredPose = {
      name,
      poseData: file,
      metadata: {
        originalFileName: file.name,
        uploadedAt: Date.now(),
        fileSize: file.size,
      },
    };
    await storageServiceProxy.fileSave(id, pose, "pose");
    return id;
  }

  async getPose(id: string): Promise<StoredPose | null> {
    const value = await storageServiceProxy.fileLoad(id);
    if (
      !isRecord(value) ||
      typeof value.name !== "string" ||
      !(value.poseData instanceof Blob)
    )
      return null;
    return value as unknown as StoredPose;
  }

  async getPosesList(): Promise<PoseListItem[]> {
    const records =
      await storageServiceProxy.filesGetMetadataByCategory("pose");
    if (!isRecord(records)) return [];
    return Object.entries(records).flatMap(([id, entry]) => {
      if (!isRecord(entry) || !isRecord(entry.value)) return [];
      return [
        {
          id,
          name:
            typeof entry.value.name === "string" ? entry.value.name : "Pose",
        },
      ];
    });
  }

  async deletePose(id: string): Promise<void> {
    await storageServiceProxy.fileRemove(id);
  }
}

export default new PoseStorageService();
