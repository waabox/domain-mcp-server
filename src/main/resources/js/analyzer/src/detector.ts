import type { FrameworkInfo } from './types';

interface PackageJsonData {
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
}

/**
 * Detects the framework and features from package.json content.
 */
export function detectFramework(packageJsonContent: string): FrameworkInfo {
  let pkg: PackageJsonData;
  try {
    pkg = JSON.parse(packageJsonContent);
  } catch {
    return { name: 'unknown', sourceRoot: 'src', features: {} };
  }

  const allDeps = {
    ...(pkg.dependencies || {}),
    ...(pkg.devDependencies || {}),
  };

  const features: Record<string, string> = {};

  if (allDeps['typescript']) {
    features['typescript'] = 'true';
  }

  // NestJS
  if (allDeps['@nestjs/core']) {
    features['decorators'] = 'true';
    return { name: 'nestjs', sourceRoot: 'src', features };
  }

  // Next.js
  if (allDeps['next']) {
    const hasAppDir = features['appRouter'] || 'unknown';
    features['router'] = hasAppDir;
    return { name: 'nextjs', sourceRoot: 'src', features };
  }

  // Nuxt
  if (allDeps['nuxt'] || allDeps['nuxt3']) {
    return { name: 'nuxt', sourceRoot: 'src', features };
  }

  // Angular
  if (allDeps['@angular/core']) {
    features['decorators'] = 'true';
    return { name: 'angular', sourceRoot: 'src', features };
  }

  // Vue
  if (allDeps['vue']) {
    return { name: 'vue', sourceRoot: 'src', features };
  }

  // Remix
  if (allDeps['@remix-run/node'] || allDeps['@remix-run/react']) {
    return { name: 'remix', sourceRoot: 'app', features };
  }

  // SvelteKit
  if (allDeps['@sveltejs/kit']) {
    return { name: 'sveltekit', sourceRoot: 'src', features };
  }

  // Astro
  if (allDeps['astro']) {
    return { name: 'astro', sourceRoot: 'src', features };
  }

  // Fastify
  if (allDeps['fastify']) {
    return { name: 'fastify', sourceRoot: 'src', features };
  }

  // Express
  if (allDeps['express']) {
    return { name: 'express', sourceRoot: 'src', features };
  }

  return { name: 'unknown', sourceRoot: 'src', features };
}
