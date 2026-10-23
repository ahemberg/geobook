import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(cleanup);

// jsdom has no native dialog implementation; browser tests cover focus trapping and Escape.
HTMLDialogElement.prototype.showModal = function () {
  this.setAttribute('open', '');
};
HTMLDialogElement.prototype.close = function () {
  this.removeAttribute('open');
};

// jsdom has no canvas; accept Leaflet's canvas drawing calls without rendering.
HTMLCanvasElement.prototype.getContext = function () {
  return new Proxy({}, { get: () => () => undefined });
} as unknown as typeof HTMLCanvasElement.prototype.getContext;
