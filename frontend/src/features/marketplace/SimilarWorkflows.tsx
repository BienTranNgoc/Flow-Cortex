import { useTranslation } from 'react-i18next';
import { MarketplaceCard } from './MarketplaceCard';
import { useSimilar } from './useMarketplace';
import './MarketplaceGrid.css';

/** Up to 8 workflows like this one; nothing shows while they load, when there are none or when the read fails. */
export function SimilarWorkflows({ workflowId }: Readonly<{ workflowId: string }>) {
  const { t } = useTranslation();
  const similar = useSimilar(workflowId);
  const items = similar.data ?? [];
  if (items.length === 0) return null;

  return (
    <section className="mp-section mp-section--similar" aria-labelledby="mp-similar-title">
      <h2 id="mp-similar-title" className="mp-section__title">
        {t('workflow.marketplace.similar.heading')}
      </h2>
      <ul className="mp-grid">
        {items.map((item) => (
          <li key={item.workflow.id ?? item.workflow.name}>
            <MarketplaceCard item={item} />
          </li>
        ))}
      </ul>
    </section>
  );
}
