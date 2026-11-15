import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
} from "react";
import Dialog from "../common/Dialog";
import Icon from "../icons/Icon";
import { Button } from "../ui";
import poseStorageService, {
  type PoseListItem,
} from "../../services/storage/PoseStorageService";
import SettingsScrollArea from "./shared/SettingsScrollArea";

interface PoseLibrarySettingsProps {
  isLightBackground: boolean;
  refreshTrigger: number;
}

const getErrorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

export default function PoseLibrarySettings({
  isLightBackground,
  refreshTrigger,
}: PoseLibrarySettingsProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [poses, setPoses] = useState<PoseListItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isImporting, setIsImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<PoseListItem | null>(null);

  const loadPoses = useCallback(async () => {
    setIsLoading(true);
    try {
      setPoses(await poseStorageService.getPosesList());
      setError(null);
    } catch (loadError) {
      setError(getErrorMessage(loadError));
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadPoses();
  }, [loadPoses, refreshTrigger]);

  const handleFileChange = async (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!files.length) return;

    setIsImporting(true);
    setError(null);
    setFeedback(null);
    try {
      const errors: string[] = [];
      let imported = 0;
      for (const file of files) {
        try {
          await poseStorageService.savePose(file);
          imported++;
        } catch (importError) {
          errors.push(`${file.name}: ${getErrorMessage(importError)}`);
        }
      }
      await loadPoses();
      if (imported)
        setFeedback(
          `Imported ${imported} ${imported === 1 ? "pose" : "poses"}.`,
        );
      if (errors.length) setError(errors.join(" "));
    } catch (importError) {
      setError(getErrorMessage(importError));
    } finally {
      setIsImporting(false);
    }
  };

  const confirmDelete = async (poseId?: string) => {
    if (!poseId) throw new Error("The selected pose is unavailable.");
    const pose = poses.find((item) => item.id === poseId);
    await poseStorageService.deletePose(poseId);
    setPoses((current) => current.filter((item) => item.id !== poseId));
    setFeedback(`Deleted ${pose?.name ?? "pose"}.`);
    setError(null);
  };

  const deletingPose = pendingDelete
    ? (poses.find((pose) => pose.id === pendingDelete.id) ?? pendingDelete)
    : null;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1">
        <h4 className="settings-group-heading">Pose Library</h4>
        <p className="text-xs vassist-text-muted">
          Import and manage saved VPD poses.
        </p>
      </div>

      <input
        ref={fileInputRef}
        type="file"
        multiple
        accept=".vpd,application/octet-stream"
        onChange={(event) => void handleFileChange(event)}
        className="hidden"
        aria-label="Import VPD pose"
      />
      <Button
        variant="unstyled"
        onClick={() => fileInputRef.current?.click()}
        disabled={isImporting}
        className="flex w-full items-center justify-center gap-2 rounded-lg border border-dashed border-current/20 p-3 text-sm vassist-text-primary transition-colors hover:border-current/40 disabled:cursor-not-allowed disabled:opacity-50"
      >
        <Icon
          name={isImporting ? "loading-2" : "upload"}
          size={16}
          className={isImporting ? "animate-spin" : ""}
        />
        {isImporting ? "Importing pose…" : "Import VPD Pose"}
      </Button>

      {error && (
        <p
          role="alert"
          className="rounded-lg border border-red-400/20 bg-red-500/10 p-3 text-xs text-red-200"
        >
          {error}
        </p>
      )}
      {feedback && (
        <p role="status" className="text-xs vassist-text-muted">
          {feedback}
        </p>
      )}

      {isLoading ? (
        <p className="py-2 text-center text-xs vassist-text-muted">
          Loading saved poses…
        </p>
      ) : poses.length > 0 ? (
        <SettingsScrollArea className="max-h-[240px] overflow-y-auto flex flex-col gap-2 hover-scrollbar scrollbar-glass">
          {poses.map((pose) => (
            <div
              key={pose.id}
              className="settings-list-row settings-list-row-inline"
            >
              <div className="min-w-0 flex-1">
                <p
                  className="truncate text-sm vassist-text-primary"
                  title={pose.name}
                >
                  {pose.name}
                </p>
                <p className="text-[10px] vassist-text-muted">VPD pose</p>
              </div>
              <Button
                variant="unstyled"
                onClick={() => {
                  setError(null);
                  setPendingDelete(pose);
                }}
                className="rounded p-1 vassist-text-muted transition-colors hover:bg-red-500/20 hover:text-red-300"
                title={`Delete ${pose.name}`}
                aria-label={`Delete ${pose.name}`}
              >
                <Icon name="trash-2" size={16} />
              </Button>
            </div>
          ))}
        </SettingsScrollArea>
      ) : (
        <p className="py-2 text-center text-xs vassist-text-muted">
          No saved poses yet.
        </p>
      )}

      {deletingPose && (
        <Dialog
          type="delete"
          title={`Delete ${deletingPose.name}?`}
          message="This will remove the saved pose from your library."
          itemId={deletingPose.id}
          confirmLabel="Delete"
          isLightBackground={isLightBackground}
          onConfirm={confirmDelete}
          onConfirmComplete={() => setPendingDelete(null)}
          onCancel={() => setPendingDelete(null)}
        />
      )}
    </div>
  );
}
