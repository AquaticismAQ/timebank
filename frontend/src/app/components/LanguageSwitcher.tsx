import { useTranslation } from 'react-i18next';
import { Globe } from 'lucide-react';
import { useState, useRef, useEffect } from 'react';

export function LanguageSwitcher({ compact = false }: { compact?: boolean }) {
  const { i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const languages: { code: string; label: string; short: string }[] = [
    { code: 'zh', label: '中文', short: '中' },
    { code: 'en', label: 'English', short: 'EN' },
  ];

  const current = languages.find(l => l.code === i18n.language) ?? languages[0];

  const changeLang = (code: string) => {
    i18n.changeLanguage(code);
    localStorage.setItem('timebank-lang', code);
    setOpen(false);
  };

  return (
    <div ref={ref} className="relative">
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-1 px-2 py-1.5 rounded-lg text-muted-foreground hover:text-foreground hover:bg-accent transition-colors text-xs"
        title={current.label}
      >
        <Globe className="w-3.5 h-3.5" />
        {!compact && <span>{current.short}</span>}
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-1 bg-card border border-border rounded-xl shadow-lg z-50 min-w-28 overflow-hidden">
          {languages.map(lang => (
            <button
              key={lang.code}
              onClick={() => changeLang(lang.code)}
              className={`w-full flex items-center gap-2 px-3 py-2 text-sm transition-colors ${
                lang.code === i18n.language
                  ? 'text-primary font-medium bg-primary/5'
                  : 'text-foreground hover:bg-accent'
              }`}
            >
              {lang.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
