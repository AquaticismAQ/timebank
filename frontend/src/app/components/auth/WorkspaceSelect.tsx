import { useNavigate, Navigate } from 'react-router';
import { GraduationCap, Shield, Clock } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../App';
import { LanguageSwitcher } from '../LanguageSwitcher';

export function WorkspaceSelect() {
  const { t } = useTranslation();
  const { user, setWorkspace, logout } = useAuth();
  const navigate = useNavigate();

  if (!user) return <Navigate to="/login" replace />;

  const options = [
    {
      workspace: 'student' as const,
      icon: <GraduationCap className="w-10 h-10" />,
      title: t('auth.workspace.studentPortal'),
      desc: t('auth.workspace.studentPortalDesc'),
      color: 'from-blue-500 to-blue-600',
      available: user.workspaces.includes('student'),
    },
    {
      workspace: 'admin' as const,
      icon: <Shield className="w-10 h-10" />,
      title: t('auth.workspace.adminPortal'),
      desc: t('auth.workspace.adminPortalDesc'),
      color: 'from-violet-500 to-violet-600',
      available: user.workspaces.includes('admin'),
    },
  ];

  const handleSelect = (ws: 'student' | 'admin') => {
    setWorkspace(ws);
    navigate(ws === 'student' ? '/student/home' : '/admin/dashboard');
  };

  return (
    <div className="min-h-screen flex flex-col items-center justify-center bg-gradient-to-br from-background to-secondary/30 px-4">
      <div className="absolute top-4 right-4">
        <LanguageSwitcher />
      </div>
      <div className="w-full max-w-lg text-center">
        <div className="inline-flex items-center justify-center w-14 h-14 bg-primary rounded-2xl mb-4 shadow-lg">
          <Clock className="w-7 h-7 text-primary-foreground" />
        </div>
        <h1 className="text-foreground mb-2">{t('auth.workspace.welcomeBack')}{user.displayName}</h1>
        <p className="text-muted-foreground mb-8">{t('auth.workspace.selectWorkspace')}</p>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
          {options.map(opt => (
            <button
              key={opt.workspace}
              disabled={!opt.available}
              onClick={() => handleSelect(opt.workspace)}
              className={`p-6 rounded-2xl border-2 text-left transition-all ${
                opt.available
                  ? 'border-border hover:border-primary hover:shadow-lg cursor-pointer bg-card hover:bg-accent/50'
                  : 'border-border/50 opacity-40 cursor-not-allowed bg-card/50'
              }`}
            >
              <div className={`inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-gradient-to-br ${opt.color} text-white mb-4`}>
                {opt.icon}
              </div>
              <div className="text-foreground mb-1">{opt.title}</div>
              <p className="text-sm text-muted-foreground">{opt.desc}</p>
            </button>
          ))}
        </div>

        <button
          onClick={() => { logout(); navigate('/login'); }}
          className="text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          {t('auth.workspace.logout')}
        </button>
      </div>
    </div>
  );
}
