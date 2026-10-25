import React from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource/source-sans-3/400.css';
import '@fontsource/source-sans-3/600.css';
import '@fontsource/source-sans-3/700.css';
import './style.css';
import Account from './Account';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Account />
  </React.StrictMode>,
);
