export function BrandPanel() {
  return (
    <aside className="brand-panel" aria-hidden="false">
      <div className="brand-panel__inner">
        <p className="eyebrow">FLOWCORTEX AI 2.5</p>
        <h2 className="brand-panel__headline">
          Your AI media <span>studio</span> for production-grade pipelines.
        </h2>
        <p className="brand-panel__lede">
          Source, process, review and export media in one click — built for creators
          who need consistent output, not another toy.
        </p>
        <ul className="brand-panel__bullets">
          <li>
            <span className="brand-panel__bullet-dot" aria-hidden="true" />
            <span>Three production pipelines ready out of the box</span>
          </li>
          <li>
            <span className="brand-panel__bullet-dot" aria-hidden="true" />
            <span>Human review gates built into every multi-step run</span>
          </li>
          <li>
            <span className="brand-panel__bullet-dot" aria-hidden="true" />
            <span>Output retention and signed download links by default</span>
          </li>
        </ul>
      </div>
    </aside>
  );
}
