import TopBar from '@/components/landing/TopBar';
import Hero from '@/components/landing/Hero';
import HowItWorks from '@/components/landing/HowItWorks';
import WhyDifferent from '@/components/landing/WhyDifferent';
import Audiences from '@/components/landing/Audiences';
import CTASection from '@/components/landing/CTASection';
import SiteFooter from '@/components/landing/SiteFooter';
import styles from './Landing.module.css';

// Thin composition only — each section is its own component under
// components/landing/ so the finalised design can be swapped in one
// section at a time.
export default function Landing() {
    return (
        <div className={styles.page}>
            <TopBar />
            <main className={styles.main}>
                <Hero />
                <HowItWorks />
                <WhyDifferent />
                <Audiences />
                <CTASection />
            </main>
            <SiteFooter />
        </div>
    );
}
