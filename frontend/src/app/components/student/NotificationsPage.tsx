import { useState } from 'react';
import { Bell, CheckCheck, ChevronRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { toast } from 'sonner';
import { api } from '../../api/client';
import { usePagedApi } from '../../api/useApi';
import { PageControls } from '../PageControls';
import { ListStateBanner } from '../ListStateBanner';

const categoryColors: Record<string, string> = {
  review: 'bg-blue-100 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400',
  project: 'bg-green-100 dark:bg-green-900/30 text-green-600 dark:text-green-400',
  account: 'bg-yellow-100 dark:bg-yellow-900/30 text-yellow-600 dark:text-yellow-400',
  rules: 'bg-purple-100 dark:bg-purple-900/30 text-purple-600 dark:text-purple-400',
  system: 'bg-muted text-muted-foreground',
};

const categoryLabels: Record<string, string> = {
  review: 'student.notifications.categoryReview',
  project: 'student.notifications.categoryProject',
  account: 'student.notifications.categoryAccount',
  rules: 'student.notifications.categoryRules',
  system: 'student.notifications.categorySystem',
};

export function NotificationsPage() {
  const { t } = useTranslation();
  const [filterTab, setFilterTab] = useState<'all' | 'unread'>('all');
  const { items, page, loading, error, setPage, setFilter, refetch } = usePagedApi(api.notifications.list, {});

  const unreadCount = items.filter(n => !n.isRead).length;

  const selectFilterTab = (val: 'all' | 'unread') => {
    setFilterTab(val);
    setFilter('read', val === 'unread' ? 'false' : undefined);
  };

  const markRead = (id: string) => {
    api.notifications.markRead(id).then(refetch).catch(() => {});
  };

  const markAllRead = () => {
    api.notifications.markAllRead().then(refetch).catch(() => {});
    toast.success(t('student.notifications.allMarkedRead'));
  };

  return (
    <div className="p-4 lg:p-6 space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-foreground">{t('student.notifications.title')}</h2>
          {unreadCount > 0 && <p className="text-xs text-muted-foreground">{unreadCount} {t('student.notifications.unread')}</p>}
        </div>
        {unreadCount > 0 && (
          <button onClick={markAllRead} className="flex items-center gap-1.5 text-xs text-primary hover:text-primary/80">
            <CheckCheck className="w-4 h-4" />
            {t('student.notifications.markAllRead')}
          </button>
        )}
      </div>

      {/* Filter tabs */}
      <div className="flex gap-2">
        {[['all', t('student.notifications.all')], ['unread', t('student.notifications.unreadTab')]].map(([val, label]) => (
          <button key={val} onClick={() => selectFilterTab(val as 'all' | 'unread')}
            className={`px-4 py-1.5 rounded-full text-sm transition-colors ${
              filterTab === val ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground hover:bg-accent'
            }`}>
            {label}
          </button>
        ))}
      </div>

      {/* Notifications */}
      <div className="space-y-2">
        {items.map(notif => (
            <div
              key={notif.id}
              onClick={() => markRead(notif.id)}
              className={`bg-card border rounded-xl p-4 cursor-pointer hover:bg-accent/50 transition-colors ${
                !notif.isRead ? 'border-primary/30' : 'border-border'
              }`}
            >
              <div className="flex items-start gap-3">
                <div className={`shrink-0 text-xs px-2 py-0.5 rounded-full mt-0.5 ${categoryColors[notif.category]}`}>
                  {t(categoryLabels[notif.category])}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <div className={`text-sm flex-1 ${!notif.isRead ? 'text-foreground font-medium' : 'text-foreground'}`}>
                      {notif.title}
                    </div>
                    {!notif.isRead && <div className="w-2 h-2 bg-primary rounded-full shrink-0" />}
                  </div>
                  <div className="text-xs text-muted-foreground mt-1 leading-relaxed">{notif.body}</div>
                  <div className="text-xs text-muted-foreground mt-1.5">{notif.createdAt.slice(0, 16).replace('T', ' ')}</div>
                </div>
              </div>
            </div>
        ))}
      </div>
      <ListStateBanner error={error} empty={!loading && items.length === 0} emptyLabel={t('student.notifications.noNotifications')} />
      <PageControls page={page} onPageChange={setPage} />
    </div>
  );
}
