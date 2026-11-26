import { BookmarkSimple, Star } from '@phosphor-icons/react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { Item, MarketplaceItem } from '@flow-cortex/api-client';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { problemDetailOf } from '../../lib/problemMessage';
import { formatCredits } from '../credits/formatCredits';
import { iconForWorkflow } from '../dashboard/workflowIconKey';
import { WorkflowIcon } from '../dashboard/WorkflowIcon';
import { useBookmarkToggle } from './useMarketplace';
import './MarketplaceCard.css';

type MarketplaceCardProps =
  /** A catalogue, recommended or similar item: it carries the author and the figures. */
  | { readonly item: MarketplaceItem; readonly saved?: undefined }
  /** A saved item, which the saved list serves without author or figures. */
  | { readonly saved: Item; readonly item?: undefined };

function RatingFigure({ item }: Readonly<{ item: MarketplaceItem }>) {
  const { t } = useTranslation();
  const { average, count } = item.rating;
  if (average === null || average === undefined || count === 0) {
    return <li>{t('workflow.marketplace.card.noRating')}</li>;
  }
  const shown = formatNumber(average, activeLocale(), {
    minimumFractionDigits: 1,
    maximumFractionDigits: 2,
  });
  return (
    <li className="mp-card__rating">
      <Star size={14} weight="fill" aria-hidden="true" />
      <span className="visually-hidden">
        {t('workflow.marketplace.card.ratingLabel', { count, average: shown })}
      </span>
      <span aria-hidden="true">
        {t('workflow.marketplace.card.rating', {
          average: shown,
          count: formatNumber(count, activeLocale()),
        })}
      </span>
    </li>
  );
}

/** One workflow: it opens `/workflows/{id}`, and its toggle saves or unsaves it. */
export function MarketplaceCard({ item, saved }: Readonly<MarketplaceCardProps>) {
  const { t } = useTranslation();
  const workflow = (item ?? saved).workflow;
  const serverState = item ? item.bookmarked : true;
  const toggle = useBookmarkToggle();
  // What the member pressed until the lists reload and say the same.
  const [pressedState, setPressedState] = useState<boolean | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  useEffect(() => {
    setPressedState(null);
  }, [serverState]);

  const pressed = pressedState ?? serverState;
  const name = workflow.name ?? t('workflow.marketplace.card.untitled');
  const steps = workflow.steps?.length ?? 0;
  const locale = activeLocale();

  const press = () => {
    if (!workflow.id || toggle.isPending) return;
    const previous = pressed;
    setFailure(null);
    setPressedState(!previous);
    toggle.mutate(
      { id: workflow.id, bookmarked: !previous },
      {
        onSuccess: (answer) => setPressedState(answer.bookmarked),
        onError: (error) => {
          setPressedState(previous);
          setFailure(problemDetailOf(error) ?? t('workflow.marketplace.card.saveFailed'));
        },
      },
    );
  };

  return (
    <article className="mp-card">
      <div className="mp-card__head">
        <span className="mp-card__icon" aria-hidden="true">
          <WorkflowIcon icon={iconForWorkflow(workflow.name)} size={18} />
        </span>
        <h3 className="mp-card__title">
          <Link
            to={workflow.id ? `/workflows/${workflow.id}` : '/workflows'}
            className="mp-card__link"
          >
            {name}
          </Link>
        </h3>
        <button
          type="button"
          className={`mp-card__save${pressed ? ' is-pressed' : ''}`}
          aria-pressed={pressed}
          aria-label={t('workflow.marketplace.card.save', { name })}
          aria-disabled={toggle.isPending || undefined}
          onClick={press}
        >
          <BookmarkSimple size={18} weight={pressed ? 'fill' : 'regular'} aria-hidden="true" />
        </button>
      </div>
      {item && (
        <p className="mp-card__author">
          {t('workflow.marketplace.card.by', {
            name: item.authorName ?? t('workflow.marketplace.card.product'),
          })}
        </p>
      )}
      <ul className="mp-card__meta">
        <li>{t('workflow.list.stepCount', { count: steps, n: formatNumber(steps, locale) })}</li>
        {typeof workflow.estimatedCredits === 'number' && (
          <li>
            {t('workflow.marketplace.card.credits', {
              amount: formatCredits(workflow.estimatedCredits, locale),
            })}
          </li>
        )}
        {item && <RatingFigure item={item} />}
        {item && (
          <li>
            {t('workflow.marketplace.card.members', {
              count: item.membersLast30Days,
              n: formatNumber(item.membersLast30Days, locale),
            })}
          </li>
        )}
      </ul>
      {failure && (
        <p role="alert" className="mp-card__error">
          {failure}
        </p>
      )}
    </article>
  );
}
