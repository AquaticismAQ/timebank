import { useState, useEffect, useMemo } from 'react';
import { Home, FolderOpen, FileText, Wallet, User, Bell, TrendingUp, Lightbulb, MessageSquareWarning } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../App';
import { api } from '../../api/client';
import { type StudentProgress, type Account } from '../../api/types';
import { formatCoins, minutesToHours } from '../../lib/format';
import { AppShell, type ShellNavItem } from './AppShell';

export function StudentLayout() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [progress, setProgress] = useState<StudentProgress | null>(null);
  const [account, setAccount] = useState<Account | null>(null);
  const [accountLoaded, setAccountLoaded] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);

  useEffect(() => {
    api.students.progress(user?.studentId || 'std_001').then(setProgress).catch(() => {});
    // `getOrNull`: no account row is a zero balance, not an error (issue 099).
    api.accounts.getOrNull(user?.studentId || 'std_001')
      .then(setAccount).catch(() => setAccount(null)).finally(() => setAccountLoaded(true));
    api.notifications.unreadCount().then(setUnreadCount).catch(() => {});
  }, [user?.studentId]);

  const bottomNavItems = useMemo((): ShellNavItem[] => [
    { to: '/student/home', icon: Home, label: t('layout.student.home') },
    { to: '/student/projects', icon: FolderOpen, label: t('layout.student.projects') },
    { to: '/student/records', icon: FileText, label: t('layout.student.records') },
    { to: '/student/account', icon: Wallet, label: t('layout.student.account') },
    { to: '/student/profile', icon: User, label: t('layout.student.profile') },
  ], [t]);

  const navItems = useMemo((): ShellNavItem[] => [
    { to: '/student/home', icon: Home, label: t('layout.student.home') },
    { to: '/student/projects', icon: FolderOpen, label: t('layout.student.projectCenter') },
    { to: '/student/records', icon: FileText, label: t('layout.student.serviceRecords') },
    { to: '/student/cases', icon: MessageSquareWarning, label: t('layout.student.cases') },
    { to: '/student/proposals', icon: Lightbulb, label: t('layout.student.eventProposals') },
    { to: '/student/account', icon: Wallet, label: t('layout.student.timeCoinAccount') },
    { to: '/student/progress', icon: TrendingUp, label: t('layout.student.progress') },
    { to: '/student/notifications', icon: Bell, label: t('layout.student.notifications'), badge: unreadCount || undefined },
    { to: '/student/profile', icon: User, label: t('layout.student.profile') },
  ], [t, unreadCount]);

  const statsStrip = (
    <div className="grid grid-cols-2 gap-2">
      <div className="bg-sidebar-accent rounded-lg p-2 text-center">
        <div className="text-sidebar-accent-foreground font-semibold text-sm">{progress ? minutesToHours(progress.completedMinutes) : '--'}h</div>
        <div className="text-sidebar-foreground/50 text-xs">{t('layout.student.completed')}</div>
      </div>
      <div className="bg-sidebar-accent rounded-lg p-2 text-center">
        {/* `--` only while the request is in flight. A student with no account
                row has a balance of zero, and showing them a dash forever reads
                as "unknown" for a figure that is known exactly (issue 099). */}
            <div className="text-sidebar-accent-foreground font-semibold text-sm">{accountLoaded ? formatCoins(account?.balance ?? 0) : '--'}</div>
        <div className="text-sidebar-foreground/50 text-xs">{t('layout.student.timeCoins')}</div>
      </div>
    </div>
  );

  return (
    <AppShell
      navItems={navItems}
      bottomNavItems={bottomNavItems}
      brandSubtitle={t('layout.student.studentPortal')}
      headerInfo={progress ? `${progress.grade} · ${progress.academicYear} ${t('common.academicYear')}` : ''}
      statsStrip={statsStrip}
      notificationsPath="/student/notifications"
      unreadCount={unreadCount}
    />
  );
}
