import AppHeader from '@/components/AppHeader';
import Hero from '@/components/landing/Hero';
import WardStrip from '@/components/landing/WardStrip';
import HowItWorks from '@/components/landing/HowItWorks';
import WhyDifferent from '@/components/landing/WhyDifferent';
import Audiences from '@/components/landing/Audiences';
import CTASection from '@/components/landing/CTASection';
import SiteFooter from '@/components/landing/SiteFooter';
import styles from './Landing.module.css';

const NAV = [
    { label: 'How it works', to: '/#how-it-works' },
    { label: 'Track a report', to: '/complaints' },
] as const;

// Thin composition only — each section owns its own component and CSS Module so
// the design can be retuned one section at a time.
export default function Landing() {
    return (
        <div className={styles.page}>
            <AppHeader nav={NAV} />
            <main className={styles.main}>
                <Hero />
                <WardStrip />
                <HowItWorks />
                <WhyDifferent />
                <Audiences />
                <CTASection />
            </main>
            <SiteFooter />
        </div>
    );
}
