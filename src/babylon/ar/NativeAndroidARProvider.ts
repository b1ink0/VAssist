import type { ARProvider } from "./ARProvider";
import {
  getNativeAndroidARBridge,
  type NativeAndroidARBridge,
} from "./NativeAndroidARBridge";
import type {
  ARAnchor,
  ARFrame,
  ARHit,
  ARLightEstimate,
  ARPlane,
  ARTrackingState,
} from "./ARTypes";

export class NativeAndroidARProvider implements ARProvider {
  private bridge: NativeAndroidARBridge | null = null;
  private unsubscribe: (() => void) | null = null;
  private readonly trackingCallbacks = new Set<
    (state: ARTrackingState) => void
  >();
  private readonly frameCallbacks = new Set<(frame: ARFrame) => void>();
  private readonly planeCallbacks = new Set<(planes: ARPlane[]) => void>();
  private readonly anchorCallbacks = new Set<(anchor: ARAnchor) => void>();
  private readonly lightCallbacks = new Set<
    (estimate: ARLightEstimate) => void
  >();
  private receivedFirstFrame = false;
  private lastTrackingState: ARTrackingState["state"] | null = null;

  constructor() {
    this.bridge = getNativeAndroidARBridge();
    if (!this.bridge) {
      console.error("[VASSIST_AR] bridge:NativeARPlugin unavailable");
      return;
    }

    console.info("[VASSIST_AR] bridge:connected");
    this.unsubscribe = this.bridge.subscribe((message) =>
      this.handleMessage(message),
    );
  }

  private handleMessage(data: Record<string, unknown>): void {
    try {
      if (data.type === "frame") {
        const frame = data.frame as ARFrame;
        if (!this.receivedFirstFrame) {
          this.receivedFirstFrame = true;
          console.info("[VASSIST_AR] bridge:first-frame", frame.timestamp);
        }
        this.frameCallbacks.forEach((callback) => callback(frame));

        const planes = data.planes as ARPlane[] | undefined;
        if (planes) {
          this.planeCallbacks.forEach((callback) => callback(planes));
        }

        const anchors = data.anchors as ARAnchor[] | undefined;
        anchors?.forEach((anchor) => {
          this.anchorCallbacks.forEach((callback) => callback(anchor));
        });

        const light = data.lightEstimate as ARLightEstimate | undefined;
        if (light) {
          this.lightCallbacks.forEach((callback) => callback(light));
        }

        const trackingState = data.trackingState as ARTrackingState | undefined;
        if (trackingState) {
          if (this.lastTrackingState !== trackingState.state) {
            this.lastTrackingState = trackingState.state;
            console.info(`[VASSIST_AR] bridge:tracking=${trackingState.state}`);
          }
          this.trackingCallbacks.forEach((callback) => callback(trackingState));
        }
        return;
      }
    } catch (error) {
      console.error("Error parsing native AR message", error);
    }
  }

  private sendWithPromise(
    payload: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    if (!this.bridge)
      return Promise.reject(new Error("Native AR bridge is unavailable"));
    return this.bridge.request(payload);
  }

  public async isSupported(): Promise<boolean> {
    try {
      const response = await this.sendWithPromise({
        action: "checkSupported",
      });
      return response.supported === true;
    } catch {
      return false;
    }
  }

  public async start(): Promise<void> {
    const response = await this.sendWithPromise({ action: "startSession" });
    if (response.success !== true) {
      throw new Error(String(response.error ?? "Unable to start AR session"));
    }
  }

  public async stop(): Promise<void> {
    await this.sendWithPromise({ action: "stopSession" });
  }

  public onTrackingStateChanged(
    callback: (state: ARTrackingState) => void,
  ): void {
    this.trackingCallbacks.add(callback);
  }

  public onCameraFrame(callback: (frame: ARFrame) => void): void {
    this.frameCallbacks.add(callback);
  }

  public onPlanesChanged(callback: (planes: ARPlane[]) => void): void {
    this.planeCallbacks.add(callback);
  }

  public onAnchorUpdated(callback: (anchor: ARAnchor) => void): void {
    this.anchorCallbacks.add(callback);
  }

  public onLightEstimateChanged(
    callback: (estimate: ARLightEstimate) => void,
  ): void {
    this.lightCallbacks.add(callback);
  }

  public async hitTest(x: number, y: number): Promise<ARHit | null> {
    const response = await this.sendWithPromise({ action: "hitTest", x, y });
    return (response.result as ARHit | null) ?? null;
  }

  public async createAnchor(hit: ARHit): Promise<ARAnchor> {
    const response = await this.sendWithPromise({
      action: "createAnchor",
      worldX: hit.x,
      worldY: hit.y,
      worldZ: hit.z,
    });
    const anchor = response.anchor as ARAnchor | null;
    if (!anchor) throw new Error("ARCore could not create an anchor");
    return anchor;
  }

  public dispose(): void {
    this.unsubscribe?.();
    this.unsubscribe = null;
  }
}
