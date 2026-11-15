type NativeMessagePort = {
  addEventListener(
    type: "message",
    callback: (event: MessageEvent<string>) => void,
  ): void;
  postMessage(message: string): void;
};

export interface ARCaptureResult {
  success: boolean;
  uri?: string;
  error?: string;
}

type NativeMessage = Record<string, unknown>;

/** Shared transport for the native AR session and its capture operations. */
export class NativeAndroidARBridge {
  private readonly listeners = new Set<(message: NativeMessage) => void>();
  private readonly pending = new Map<
    string,
    {
      resolve: (message: NativeMessage) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  private nextId = 0;

  constructor(private readonly port: NativeMessagePort) {
    port.addEventListener("message", (event) => {
      let message: NativeMessage;
      try {
        message = JSON.parse(event.data) as NativeMessage;
      } catch {
        return;
      }
      const id = message.messageId;
      if (typeof id === "string") {
        const request = this.pending.get(id);
        if (request) {
          clearTimeout(request.timer);
          this.pending.delete(id);
          request.resolve(message);
        }
      }
      this.listeners.forEach((listener) => listener(message));
    });
  }

  subscribe(listener: (message: NativeMessage) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  request(payload: NativeMessage, timeoutMs = 5000): Promise<NativeMessage> {
    return new Promise((resolve, reject) => {
      const messageId = `ar_${this.nextId++}`;
      const timer = setTimeout(() => {
        this.pending.delete(messageId);
        reject(
          new Error(`Native AR request timed out: ${String(payload.action)}`),
        );
      }, timeoutMs);
      this.pending.set(messageId, { resolve, timer });
      try {
        this.port.postMessage(JSON.stringify({ ...payload, messageId }));
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(messageId);
        reject(error);
      }
    });
  }

  async capturePhoto(canvas: HTMLCanvasElement): Promise<ARCaptureResult> {
    const view = canvas.ownerDocument.defaultView;
    if (!view || view.innerWidth <= 0 || view.innerHeight <= 0) {
      throw new Error("The avatar viewport is not ready for capture.");
    }
    const rect = canvas.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) {
      throw new Error("The avatar canvas is not ready for capture.");
    }
    return this.captureRequest("capturePhoto", {
      avatarPngDataUrl: canvas.toDataURL("image/png"),
      avatarRect: {
        left: rect.left / view.innerWidth,
        top: rect.top / view.innerHeight,
        width: rect.width / view.innerWidth,
        height: rect.height / view.innerHeight,
      },
    });
  }
  async startVideo(options: {
    mediaAudio: boolean;
    microphone: boolean;
  }): Promise<ARCaptureResult> {
    return this.captureRequest("startVideo", options);
  }
  async stopVideo(): Promise<ARCaptureResult> {
    return this.captureRequest("stopVideo");
  }
  async shareCapture(uri: string): Promise<ARCaptureResult> {
    return this.captureRequest("shareCapture", { uri });
  }

  onVideoStopped(listener: (result: ARCaptureResult) => void): () => void {
    return this.subscribe((message) => {
      if (message.type === "videoStopped")
        listener(this.captureResult(message));
    });
  }

  private captureResult(message: NativeMessage): ARCaptureResult {
    return {
      success: message.success === true,
      ...(typeof message.uri === "string" ? { uri: message.uri } : {}),
      ...(typeof message.error === "string" ? { error: message.error } : {}),
    };
  }

  private async captureRequest(
    action: string,
    payload: NativeMessage = {},
  ): Promise<ARCaptureResult> {
    const timeout =
      action === "startVideo" || action === "stopVideo"
        ? 120000
        : action === "capturePhoto"
          ? 60000
          : 10000;
    const message = await this.request({ action, ...payload }, timeout);
    if (
      message.type !== `${action}Result` ||
      typeof message.success !== "boolean"
    ) {
      throw new Error("Invalid response from Android AR capture.");
    }
    return this.captureResult(message);
  }
}

let cachedPort: NativeMessagePort | undefined;
let cachedBridge: NativeAndroidARBridge | null = null;

export function getNativeAndroidARBridge(): NativeAndroidARBridge | null {
  const port =
    typeof window === "undefined" ? undefined : window.NativeARPlugin;
  if (!port) return null;
  if (cachedPort !== port) {
    cachedPort = port;
    cachedBridge = new NativeAndroidARBridge(port);
  }
  return cachedBridge;
}

declare global {
  interface Window {
    NativeARPlugin?: NativeMessagePort;
  }
}
