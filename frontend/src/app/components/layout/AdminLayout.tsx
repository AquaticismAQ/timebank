import { useState, useEffect, useMemo, useCallback } from 'react';
import { LayoutDashboard, ClipboardCheck, FolderOpen, Users, Wallet, BarChart2, Settings, User, FileQuestion, Lightbulb, Bell } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../App';
import { api } from '../../api/client';
import { useLocation } from 'react-router';
import { AppShell, type ShellNavItem } from './AppShell';
import { BadgeCountsProvider } from './BadgeCounts';

export function AdminLayout() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [unreadCount, setUnreadCount] = useState(0);
  const [pendingReviews, setPendingReviews] = useState(0);
  const [pendingCases, setPendingCases] = useState(0);
  const [pendingProposals, setPendingProposals] = useState(0);

  const refresh = useCallback(() => {
    // Each is caught on its own: a portal where one queue is forbidden to this
    // user must still show the others, which is why these were never a single
    // combined request.
    api.notifications.unreadCount().then(setUnreadCount).catch(() => {});
    api.reviewQueue.summary().then(s => setPendingReviews(s.pending)).catch(() => {});
    api.cases.pendingCount().then(setPendingCases).catch(() => {});
    api.eventProposals.pendingCount().then(setPendingProposals).catch(() => {});
  }, []);

  // On arrival, and again whenever the user moves. The layout is the route
  // parent and does not remount between its children, so without the pathname
  // here this runs once per sign-in (issue 154). Navigation is the cheap half
  // of the answer: it catches anything decided on another screen, in another
  // tab, or by someone else. The precise half is `refresh` in the context
  // below, which the page that changed something calls immediately.
  const { pathname } = useLocation();
  useEffect(refresh, [refresh, pathname]);

  const navItems = useMemo((): ShellNavItem[] => [
    { to: '/admin/dashboard', icon: LayoutDashboard, label: t('layout.admin.dashboard') },
    { to: '/admin/audit', icon: ClipboardCheck, label: t('layout.admin.auditCenter'), badge: pendingReviews || undefined },
    { to: '/admin/cases', icon: FileQuestion, label: t('layout.admin.caseReview'), badge: pendingCases || undefined },
    { to: '/admin/proposals', icon: Lightbulb, label: t('layout.admin.proposalReview'), badge: pendingProposals || undefined },
    { to: '/admin/projects', icon: FolderOpen, label: t('layout.admin.projectManagement') },
    { to: '/admin/students', icon: Users, label: t('layout.admin.studentManagement') },
    { to: '/admin/accounts', icon: Wallet, label: t('layout.admin.timeCoinAccounts') },
    { to: '/admin/reports', icon: BarChart2, label: t('layout.admin.reports') },
    { to: '/admin/system', icon: Settings, label: t('layout.admin.systemAdmin') },
    { to: '/admin/notifications', icon: Bell, label: t('layout.admin.notifications'), badge: unreadCount || undefined },
    { to: '/admin/profile', icon: User, label: t('layout.admin.profile') },
  ], [t, pendingReviews, pendingCases, pendingProposals, unreadCount]);

  const badgeCounts = useMemo(() => ({ refresh }), [refresh]);

  return (
    <BadgeCountsProvider value={badgeCounts}>
      <AppShell
        navItems={navItems}
        brandSubtitle={t('layout.admin.brandSubtitle')}
        unreadCount={unreadCount}
        notificationsPath="/admin/notifications"
      />
    </BadgeCountsProvider>
  );
}
