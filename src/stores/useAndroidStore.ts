import { create } from "zustand";
import type { AndroidAPI } from "../types/android";
import {
  getNativeAndroidARBridge,
  type NativeAndroidARBridge,
} from "../babylon/ar/NativeAndroidARBridge";

const getAndroidApi = (): AndroidAPI | null => {
  if (typeof window === "undefined") {
    return null;
  }

  return window.AndroidAI ?? null;
};

interface AndroidStoreState {
  api: AndroidAPI | null;
  arApi: NativeAndroidARBridge | null;
  isReady: boolean;
  refreshApi: () => void;
}

export const useAndroidStore = create<AndroidStoreState>((set) => ({
  api: getAndroidApi(),
  arApi: getNativeAndroidARBridge(),
  isReady: getAndroidApi() !== null,
  refreshApi: () => {
    const api = getAndroidApi();
    set({
      api,
      arApi: getNativeAndroidARBridge(),
      isReady: api !== null,
    });
  },
}));
