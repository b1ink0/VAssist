import AndroidTtsPackDownloader from "./AndroidTtsPackDownloader";
import type { AndroidAPI } from "../../../types/android";

interface AndroidLocalTTSConfigProps {
  androidAPI: AndroidAPI | null;
  model: string;
  speakerId: number;
  speed: number;
  onModelChange: (model: string) => void;
  onSpeakerIdChange: (speakerId: number) => void;
  onSpeedChange: (speed: number) => void;
  isLightBackground?: boolean;
  onRequestDeleteDialog?: ((packId: string) => void) | undefined;
  externalDeleteTick?: number | undefined;
}

const AndroidLocalTTSConfig = ({
  androidAPI,
  model,
  speakerId,
  speed,
  onModelChange,
  onSpeakerIdChange,
  onSpeedChange,
  isLightBackground = false,
  onRequestDeleteDialog,
  externalDeleteTick,
}: AndroidLocalTTSConfigProps) => (
  <div className="flex flex-col gap-4">
    <AndroidTtsPackDownloader
      androidAPI={androidAPI}
      isLightBackground={isLightBackground}
      activePackId={model}
      onActivePackChange={onModelChange}
      speakerId={speakerId}
      speed={speed}
      onSpeakerIdChange={onSpeakerIdChange}
      onSpeedChange={onSpeedChange}
      onRequestDeleteDialog={onRequestDeleteDialog}
      externalDeleteTick={externalDeleteTick}
    />
  </div>
);

export default AndroidLocalTTSConfig;
