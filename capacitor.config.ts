import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.gkk.app',
  appName: 'Gumasta Krishi Kendra',
  webDir: 'www',
  android: {
    allowMixedContent: true,
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    StatusBar: {
      style: 'Dark',
      backgroundColor: '#1a4a2e',
    },
  },
};

export default config;
