#include "ImageCache.h"

#include <QNetworkAccessManager>
#include <QNetworkDiskCache>
#include <QStandardPaths>

QNetworkAccessManager *ImageCache::create(QObject *parent)
{
    auto *manager = new QNetworkAccessManager(parent);
    auto *cache = new QNetworkDiskCache(manager);
    cache->setCacheDirectory(QStandardPaths::writableLocation(QStandardPaths::CacheLocation) + "/images");
    cache->setMaximumCacheSize(500LL * 1024 * 1024);
    manager->setCache(cache);
    return manager;
}
