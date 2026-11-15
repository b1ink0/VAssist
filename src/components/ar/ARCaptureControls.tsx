import { useEffect, useRef, useState, type CSSProperties } from "react";
import { useAndroidARApi } from "../../hooks/useAndroidStore";
import { useSceneRef } from "../../hooks/app/useScene";
import {
  getARPlacementState,
  onARPlacementStateChanged,
} from "../../babylon/ar/ARModeLifecycle";
import { Button } from "../ui";
import Dialog from "../common/Dialog";
import Toggle from "../common/Toggle";
import { Icon } from "../icons";
import { ANDROID_SYSTEM_INSET_BOTTOM } from "../chat/historyLayout";
import { cn } from "../../utils/cn";

const nextPaint = () =>
  new Promise<void>((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
  });

export function ARCaptureControls({
  isChatOpen = false,
  isLightBackground = false,
}: {
  isChatOpen?: boolean;
  isLightBackground?: boolean;
}) {
  const [placement, setPlacement] = useState(getARPlacementState);
  const [hidden, setHidden] = useState(false);
  const [captureOpen, setCaptureOpen] = useState(false);
  const [recording, setRecording] = useState(false);
  const [busy, setBusy] = useState(false);
  const [lastUri, setLastUri] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [confirmRecording, setConfirmRecording] = useState(false);
  const [mediaAudio, setMediaAudio] = useState(true);
  const [microphone, setMicrophone] = useState(false);
  const arApi = useAndroidARApi();
  const sceneRef = useSceneRef();
  const mounted = useRef(false);
  const recordingRef = useRef(false);

  useEffect(() => onARPlacementStateChanged(setPlacement), []);
  useEffect(() => {
    if (!arApi) return;
    return arApi.onVideoStopped((result) => {
      setRecording(false);
      recordingRef.current = false;
      setBusy(false);
      setHidden(false);
      setCaptureOpen(true);
      delete document.documentElement.dataset.arCaptureClean;
      if (result.uri) setLastUri(result.uri);
      if (result.uri) setFeedback("Video saved");
      if (!result.success) setError(result.error ?? "Could not save video.");
    });
  }, [arApi]);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      delete document.documentElement.dataset.arCaptureClean;
      if (recordingRef.current) void arApi?.stopVideo().catch(() => undefined);
      recordingRef.current = false;
    };
  }, [arApi]);

  useEffect(() => {
    if (placement === "inactive") {
      if (recordingRef.current) {
        recordingRef.current = false;
        void arApi?.stopVideo().catch(() => undefined);
        setRecording(false);
      }
      setHidden(false);
      setCaptureOpen(false);
      delete document.documentElement.dataset.arCaptureClean;
    }
  }, [placement, arApi]);

  useEffect(() => {
    if (!feedback) return;
    const timeout = setTimeout(() => setFeedback(null), 4000);
    return () => clearTimeout(timeout);
  }, [feedback]);

  const capture = async (video: boolean) => {
    setBusy(true);
    setError(null);
    setFeedback(null);
    document.documentElement.dataset.arCaptureClean = "capture";
    try {
      await nextPaint();
      if (!arApi) throw new Error("AR capture is unavailable.");
      const canvas = sceneRef.current?.getEngine().getRenderingCanvas();
      if (!video && !canvas)
        throw new Error("The avatar renderer is not ready.");
      const result = video
        ? await arApi.startVideo({ mediaAudio, microphone })
        : await arApi.capturePhoto(canvas!);
      if (!result.success) throw new Error(result.error ?? "Capture failed.");
      if (video) {
        if (!mounted.current || getARPlacementState() === "inactive") {
          await arApi.stopVideo();
          return;
        }
        recordingRef.current = true;
        setRecording(true);
      } else {
        if (result.uri) {
          setLastUri(result.uri);
          setCaptureOpen(true);
          setFeedback("Photo saved");
        }
        delete document.documentElement.dataset.arCaptureClean;
      }
    } catch (reason) {
      delete document.documentElement.dataset.arCaptureClean;
      setError(reason instanceof Error ? reason.message : "Capture failed.");
    } finally {
      setBusy(false);
    }
  };

  const share = async () => {
    if (!lastUri) return;
    setError(null);
    try {
      if (!arApi) throw new Error("AR capture is unavailable.");
      const result = await arApi.shareCapture(lastUri);
      if (!result.success)
        throw new Error(result.error ?? "Could not share capture.");
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "Could not share capture.",
      );
    }
  };

  const stopRecording = async () => {
    if (busy || !recording) return;
    setBusy(true);
    try {
      if (!arApi) throw new Error("AR capture is unavailable.");
      const result = await arApi.stopVideo();
      if (!result.success)
        throw new Error(result.error ?? "Could not save video.");
      if (result.uri) {
        setLastUri(result.uri);
        setCaptureOpen(true);
        setFeedback("Video saved");
      }
      recordingRef.current = false;
      setRecording(false);
      delete document.documentElement.dataset.arCaptureClean;
    } catch (reason) {
      recordingRef.current = false;
      setRecording(false);
      delete document.documentElement.dataset.arCaptureClean;
      setError(
        reason instanceof Error ? reason.message : "Could not save video.",
      );
    } finally {
      setBusy(false);
    }
  };

  if (!arApi || placement !== "placed") return null;
  const buttonClass =
    "h-12 w-12 rounded-full p-0 hover:scale-110 active:scale-95 transition-transform";
  const variant = isLightBackground ? "dark" : "default";
  const collapseDistance = (distance: number) =>
    ({ "--dock-collapse-distance": `${distance}px` }) as CSSProperties;
  return (
    <>
      {recording && (
        <button
          id="vassist-ar-stop-recording"
          type="button"
          className="fixed inset-0 z-[10030] border-0 bg-transparent p-0"
          aria-label="Stop recording"
          onClick={() => void stopRecording()}
        />
      )}
      {!recording && !hidden && (
        <div
          className={cn(
            "fixed right-3 z-[10020] flex flex-col-reverse items-center gap-2 transition-opacity duration-200 motion-reduce:transition-none",
            isChatOpen ? "pointer-events-none opacity-0" : "opacity-100",
          )}
          style={{ bottom: `calc(20px + ${ANDROID_SYSTEM_INSET_BOTTOM})` }}
          inert={isChatOpen}
          aria-hidden={isChatOpen}
          onPointerDown={(event) => event.stopPropagation()}
        >
          <Button
            variant={variant}
            className={buttonClass}
            title={captureOpen ? "Close capture controls" : "AR capture"}
            aria-label={captureOpen ? "Close capture controls" : "AR capture"}
            aria-expanded={captureOpen}
            disabled={busy}
            onClick={() => setCaptureOpen((open) => !open)}
          >
            <Icon name={captureOpen ? "close" : "camera"} size={20} />
          </Button>
          <div
            className={cn(
              "assistant-dock-controls flex flex-col-reverse gap-2",
              !captureOpen && "pointer-events-none",
            )}
            data-collapsed={!captureOpen}
            inert={!captureOpen}
            aria-hidden={!captureOpen}
          >
            <Button
              variant={variant}
              style={collapseDistance(56)}
              className={buttonClass}
              title="Hide controls"
              aria-label="Hide controls"
              disabled={busy}
              onClick={() => {
                setHidden(true);
                document.documentElement.dataset.arCaptureClean = "hidden";
              }}
            >
              <Icon name="eye-off" size={20} />
            </Button>
            <Button
              variant={variant}
              style={collapseDistance(112)}
              className={buttonClass}
              title="Take photo"
              aria-label="Take photo"
              disabled={busy}
              onClick={() => void capture(false)}
            >
              <Icon name="camera" size={21} />
            </Button>
            <Button
              variant={variant}
              style={collapseDistance(168)}
              className={buttonClass}
              title="Record video"
              aria-label="Record video"
              disabled={busy}
              onClick={() => setConfirmRecording(true)}
            >
              <Icon name="video-camera" size={21} />
            </Button>
            {lastUri && (
              <Button
                variant={variant}
                style={collapseDistance(224)}
                className={cn(buttonClass, "animate-fade-in")}
                title="Share last capture"
                aria-label="Share last capture"
                disabled={busy}
                onClick={() => void share()}
              >
                <Icon name="share" size={20} />
              </Button>
            )}
          </div>
        </div>
      )}
      {hidden && !recording && (
        <Button
          variant={variant}
          id="vassist-ar-show-controls"
          className="fixed right-3 z-[10020] h-12 w-12 rounded-full p-0"
          style={{ bottom: `calc(20px + ${ANDROID_SYSTEM_INSET_BOTTOM})` }}
          title="Show controls"
          aria-label="Show controls"
          onClick={() => {
            setHidden(false);
            delete document.documentElement.dataset.arCaptureClean;
          }}
        >
          <Icon name="eye" size={20} />
        </Button>
      )}
      {error && (
        <div
          role="alert"
          className="fixed bottom-24 left-1/2 z-[10021] w-[min(88vw,360px)] -translate-x-1/2 rounded-xl bg-black/85 px-3 py-2 text-sm text-white"
          onClick={() => setError(null)}
        >
          {error}
        </div>
      )}
      {feedback && !recording && !isChatOpen && (
        <div
          role="status"
          className={cn(
            "glass-button fixed bottom-24 right-20 z-[10021] rounded-full px-3 py-2 text-xs backdrop-blur-md",
            isLightBackground && "glass-button-dark",
          )}
        >
          {feedback}
        </div>
      )}
      {confirmRecording && (
        <Dialog
          title="Record AR video"
          message="Controls are hidden while recording. Tap the screen or use the recording notification to stop and save."
          confirmLabel="Start recording"
          isLightBackground={isLightBackground}
          onCancel={() => setConfirmRecording(false)}
          onConfirm={() => {
            setConfirmRecording(false);
            void capture(true);
          }}
        >
          <div className="flex flex-col gap-3">
            <div className="flex items-center justify-between gap-4">
              <span className="text-sm">Media audio</span>
              <Toggle
                aria-label="Media audio"
                checked={mediaAudio}
                onChange={setMediaAudio}
                isLightBackground={isLightBackground}
              />
            </div>
            <div className="flex items-center justify-between gap-4">
              <span className="text-sm">Microphone</span>
              <Toggle
                aria-label="Microphone"
                checked={microphone}
                onChange={setMicrophone}
                isLightBackground={isLightBackground}
              />
            </div>
          </div>
        </Dialog>
      )}
    </>
  );
}
