import { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.gkk.app',
  appName: 'Gumasta Krishi Kendra',
  webDir: 'www',
  bundledWebRuntime: false,
  android: {
    allowMixedContent: true,
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
  ios: {
    contentInset: 'automatic',
    scrollEnabled: true,
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 2000,
      backgroundColor: '#1a4a2e',
      showSpinner: false,
      androidSplashResourceName: 'splash',
      iosSplashResourceName: 'Default',
    },
    StatusBar: {
      style: 'Dark',
      backgroundColor: '#1a4a2e',
    },
    LocalNotifications: {
      smallIcon: 'ic_stat_icon_config_sample',
      iconColor: '#1a4a2e',
      sound: 'beep.wav',
    },
    Camera: {
      // permissions declared here
    },
  },
};

export default config;
