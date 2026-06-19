namespace webapi.Data
{
    using Microsoft.EntityFrameworkCore;
    using webapi.Models;

    public class AppDbContext : DbContext
    {
        public AppDbContext(DbContextOptions<AppDbContext> options) : base(options)
        {
            ChangeTracker.QueryTrackingBehavior = QueryTrackingBehavior.NoTracking;
        }

        public DbSet<InspectionRecord> InspectionRecords { get; set; }
        public DbSet<InspectionTemplate> InspectionTemplates { get; set; }
        public DbSet<InspectionResult> InspectionResults { get; set; }
        public DbSet<InspectionSignature> InspectionSignatures { get; set; }
        public DbSet<InspectionUser> InspectionUsers { get; set; }
        public DbSet<QualifiedInspector> QualifiedInspectors { get; set; }
        public DbSet<Device> Devices { get; set; }
        public DbSet<InspectionPhoto> InspectionPhotos { get; set; }

        protected override void OnModelCreating(ModelBuilder modelBuilder)
        {
            base.OnModelCreating(modelBuilder);

            // 指定表名（兼容已有的表）
            modelBuilder.Entity<InspectionRecord>(entity =>
            {
                entity.ToTable("inspection_records");
                entity.Property(r => r.Frequency)
                    .HasColumnName("frequency")
                    .HasMaxLength(5)
                    .HasDefaultValue("日");
                entity.Property(r => r.PeriodKey)
                    .HasColumnName("period_key")
                    .HasMaxLength(10)
                    .HasDefaultValue("");
                entity.Property(r => r.Status)
                    .HasColumnName("status")
                    .HasMaxLength(20)
                    .HasDefaultValue("submitted");
                entity.HasIndex(r => new { r.DeviceModel, r.Frequency, r.PeriodKey })
                    .IsUnique()
                    .HasDatabaseName("idx_records_period");
                entity.HasIndex(r => new { r.DeviceModel, r.InspectionTime })
                    .HasDatabaseName("idx_records_device_time");
            });

            modelBuilder.Entity<InspectionTemplate>(entity =>
            {
                entity.ToTable("inspection_templates");
                entity.Property(t => t.Frequency)
                    .HasColumnName("frequency")
                    .HasMaxLength(5)
                    .HasDefaultValue("日");
                entity.Property(t => t.IsMandatory)
                    .HasColumnName("is_mandatory")
                    .HasDefaultValue(true);
            });

            modelBuilder.Entity<InspectionResult>(entity =>
            {
                entity.ToTable("inspection_results");
                entity.HasOne(r => r.InspectionRecord)
                    .WithMany()
                    .HasForeignKey(r => r.RecordId)
                    .OnDelete(DeleteBehavior.Cascade);
                entity.HasIndex(r => new { r.RecordId, r.ItemName })
                    .IsUnique()
                    .HasDatabaseName("uq_results_record_item");
            });

            modelBuilder.Entity<InspectionUser>(entity =>
            {
                entity.ToTable("inspection_users");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.Username).HasColumnName("username");
                entity.Property(e => e.Password).HasColumnName("password");
                entity.Property(e => e.Role).HasColumnName("role");
                entity.Property(e => e.FullName).HasColumnName("full_name");
                entity.Property(e => e.CreatedAt).HasColumnName("created_at");
            });

            modelBuilder.Entity<QualifiedInspector>(entity =>
            {
                entity.ToTable("qualified_inspectors");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.EmployeeId).HasColumnName("employee_id").HasMaxLength(9).IsRequired();
                entity.Property(e => e.LastName).HasColumnName("last_name").HasMaxLength(6).IsRequired();
                entity.HasIndex(e => e.EmployeeId)
                    .IsUnique()
                    .HasDatabaseName("idx_qualified_inspectors_employee_id");
            });

            modelBuilder.Entity<InspectionPhoto>(entity =>
            {
                entity.ToTable("inspection_photos");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.RecordId).HasColumnName("record_id").IsRequired();
                entity.Property(e => e.ItemName).HasColumnName("item_name").HasMaxLength(100).IsRequired();
                entity.Property(e => e.PhotoPath).HasColumnName("photo_path").HasMaxLength(500).IsRequired();
                entity.Property(e => e.ThumbnailPath).HasColumnName("thumbnail_path").HasMaxLength(500);
                entity.Property(e => e.PhotoOrder).HasColumnName("photo_order").HasDefaultValue(0);
                entity.Property(e => e.UploadedBy).HasColumnName("uploaded_by").HasMaxLength(50).HasDefaultValue("");
                entity.Property(e => e.CreatedAt).HasColumnName("created_at").HasDefaultValueSql("CURRENT_TIMESTAMP");
                entity.HasIndex(e => e.RecordId).HasDatabaseName("idx_photos_record");
                entity.HasIndex(e => new { e.RecordId, e.ItemName }).HasDatabaseName("idx_photos_item");
            });

            modelBuilder.Entity<Device>(entity =>
            {
                entity.ToTable("devices");
                entity.HasKey(e => e.Id);
                entity.Property(e => e.Id).HasColumnName("id");
                entity.Property(e => e.DeviceName).HasColumnName("device_name").HasMaxLength(100).IsRequired();
                entity.Property(e => e.DeviceModel).HasColumnName("device_model").HasMaxLength(50).IsRequired();
                entity.Property(e => e.DeviceLocation).HasColumnName("device_location").HasMaxLength(200);
                entity.Property(e => e.CreatedAt).HasColumnName("created_at").HasDefaultValueSql("CURRENT_TIMESTAMP");
                entity.HasIndex(e => e.DeviceModel).IsUnique().HasDatabaseName("idx_devices_device_model");
            });

            modelBuilder.Entity<InspectionSignature>(entity =>
            {
                entity.HasIndex(s => new { s.DeviceModel, s.Year, s.Month })
                    .HasDatabaseName("idx_signatures_device_period");
            });
        }
    }
}
