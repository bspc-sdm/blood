import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import '@fontsource/roboto/latin-400.css';
import '@fontsource/roboto/latin-500.css';
import '@fontsource/roboto/latin-700.css';
import '@fontsource/roboto/latin-900.css';
import '@fontsource/hind-siliguri/bengali-400.css';
import '@fontsource/hind-siliguri/bengali-500.css';
import '@fontsource/hind-siliguri/bengali-600.css';
import '@fontsource/hind-siliguri/bengali-700.css';
import '@fontsource/hind-siliguri/latin-400.css';
import './styles/tokens.css';
import './styles/base.css';
import './styles/layout.css';
import App from './App';
import { queryClient } from './lib/query';
import { SessionProvider } from './lib/session';
import { ChromeProvider } from './shell/chrome';
import { ToastHost } from './ui/kit';
import { applyTheme } from './lib/theme';
import { getLang } from './lib/i18n';

applyTheme();
document.documentElement.lang = getLang();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <SessionProvider>
          <ChromeProvider>
            <App />
            <ToastHost />
          </ChromeProvider>
        </SessionProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
