/** Builds the tile provider credit as escaped link markup for Leaflet's attribution control. */
export function mapCredit(attribution: string, attributionUrl: string) {
  const credit = document.createElement('a');
  credit.textContent = attribution;
  credit.href = /^https?:\/\//.test(attributionUrl)
    ? attributionUrl
    : 'https://www.openstreetmap.org/copyright';
  credit.target = '_blank';
  credit.rel = 'noopener noreferrer';
  return credit.outerHTML;
}
