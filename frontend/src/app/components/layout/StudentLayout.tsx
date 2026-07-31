import { useState, useEffect, useMemo } from 'react';
import { Home, FolderOpen, FileText, Wallet, User, Bell, TrendingUp, Lightbulb } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../App';
import { api } from '../../api/client';
import { type StudentProgress, type Account, type Notification } from '../../mock/data';
import { AppShell, type ShellNavItem } from './AppShell';

export function StudentLayout() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [progress, setProgress] = useState<StudentProgress | null>(null);
  const [account, setAccount] = useState<Account | null>(null);
  const [notifications, setNotifications] = useState<Notification[]>([]);

  useEffect(() => {
    api.students.progress(user?.studentId || 'std_001').then(setProgress).catch(() => {});
    api.accounts.get(user?.studentId || 'std_001').then(setAccount).catch(() => {});
    api.notifications.list().then(setNotifications).catch(() => {});
  }, [user?.studentId]);

  const unreadCount = notifications.filter(n => n.userId === user?.id && !n.isRead).length;

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
    { to: '/student/proposals', icon: Lightbulb, label: t('layout.student.eventProposals') },
    { to: '/student/account', icon: Wallet, label: t('layout.student.timeCoinAccount') },
    { to: '/student/progress', icon: TrendingUp, label: t('layout.student.progress') },
    { to: '/student/notifications', icon: Bell, label: t('layout.student.notifications'), badge: unreadCount || undefined },
    { to: '/student/profile', icon: User, label: t('layout.student.profile') },
  ], [t, unreadCount]);

  const statsStrip = (
    <div className="grid grid-cols-2 gap-2">
      <div className="bg-sidebar-accent rounded-lg p-2 text-center">
        <div className="text-sidebar-accent-foreground font-semibold text-sm">{progress?.completedHours ?? '--'}h</div>
        <div className="text-sidebar-foreground/50 text-xs">{t('layout.student.completed')}</div>
      </div>
      <div className="bg-sidebar-accent rounded-lg p-2 text-center">
        <div className="text-sidebar-accent-foreground font-semibold text-sm">{account?.balance ?? '--'}</div>
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
      switchTarget={{ workspace: 'admin', path: '/admin/dashboard', label: t('common.switchToAdmin') }}
    />
  );
}
